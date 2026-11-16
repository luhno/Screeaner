package app.screeaner;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class FecAssemblerTest {
    /** Reference implementation of the Mac-side packetizer (mac/Sources/ScreeanerCore/FecPacketizer.swift). */
    static List<byte[]> packetize(int frameId, byte[] blob, boolean key, double redundancy) {
        int size = blob.length;
        int k = Math.max(1, (size + 1199) / 1200);
        int shard = (size + k - 1) / k;
        int m = ReedSolomon.parityCount(k, redundancy);
        byte[] shards = new byte[(k + m) * shard];
        System.arraycopy(blob, 0, shards, 0, size);
        ReedSolomon.encode(shards, shard, k, m);
        List<byte[]> packets = new ArrayList<>();
        for (int i : ReedSolomon.sendOrder(k, m)) {
            byte[] p = new byte[FecAssembler.HEADER + shard];
            p[0] = (byte) (frameId >>> 24); p[1] = (byte) (frameId >>> 16); p[2] = (byte) (frameId >>> 8); p[3] = (byte) frameId;
            p[4] = (byte) (i >>> 8); p[5] = (byte) i;
            p[6] = (byte) (k >>> 8); p[7] = (byte) k;
            p[8] = (byte) (m >>> 8); p[9] = (byte) m;
            p[10] = (byte) (key ? 1 : 0);
            p[11] = FecAssembler.VERSION;
            p[12] = (byte) (size >>> 24); p[13] = (byte) (size >>> 16); p[14] = (byte) (size >>> 8); p[15] = (byte) size;
            System.arraycopy(shards, i * shard, p, FecAssembler.HEADER, shard);
            packets.add(p);
        }
        return packets;
    }

    static int index(byte[] packet) {
        return ((packet[4] & 0xFF) << 8) | (packet[5] & 0xFF);
    }

    static byte[] blob(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + seed);
        return data;
    }

    static final class Recorder implements FecAssembler.Sink {
        final List<byte[]> frames = new ArrayList<>();
        int losses;

        @Override public void onFrame(byte[] data, int length, boolean key) {
            frames.add(Arrays.copyOf(data, length));
        }

        @Override public void onLoss() {
            losses++;
        }
    }

    /** Feeds every packet whose shard index is not in {@code droppedIndexes}. */
    private static void feed(FecAssembler assembler, List<byte[]> packets, int... droppedIndexes) {
        for (byte[] p : packets) {
            boolean skip = false;
            for (int d : droppedIndexes) skip |= d == index(p);
            if (!skip) assembler.onPacket(p, p.length, 0);
        }
    }

    /** The Swift test checks the same digest, proving both ends agree on the wire format. */
    @Test public void referenceVector() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] p : packetize(7, blob(50_000, 7), true, 0.2)) digest.update(p);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        assertEquals("a3b687fbaffbc7cd73fcbc8f53b4f60858173267adef2d7cb6e01f7183944a69", hex.toString());
    }

    @Test public void fieldArithmetic() {
        for (int a = 1; a < 256; a++) assertEquals(1, ReedSolomon.mul(a, ReedSolomon.inv(a)));
        assertEquals(0, ReedSolomon.mul(0, 77));
    }

    @Test public void layoutCoversEveryShardOnce() {
        for (int k : new int[] {1, 2, 31, 32, 33, 100, 834}) {
            int m = ReedSolomon.parityCount(k, 0.2);
            int[] order = ReedSolomon.sendOrder(k, m);
            boolean[] seen = new boolean[k + m];
            for (int i : order) {
                assertFalse(seen[i]);
                seen[i] = true;
            }
            assertEquals(k + m, order.length);
        }
    }

    @Test public void deliversWithoutLoss() {
        Recorder sink = new Recorder();
        FecAssembler assembler = new FecAssembler(sink);
        int id = 1;
        for (int size : new int[] {13, 1200, 1201, 5000, 150_000, 1_000_000}) {
            sink.frames.clear();
            feed(assembler, packetize(id++, blob(size, size), true, 0.2));
            assertEquals(1, sink.frames.size());
            assertArrayEquals(blob(size, size), sink.frames.get(0));
        }
        assertEquals(0, sink.losses);
        assertEquals(0, assembler.takeStats()[1]);
    }

    @Test public void repairsScatteredLosses() {
        Recorder sink = new Recorder();
        FecAssembler assembler = new FecAssembler(sink);
        byte[] data = blob(30_000, 3); // k = 25, m = 5, one block
        // Five losses anywhere (XOR parity could not repair two in the same group).
        feed(assembler, packetize(1, data, true, 0.2), 0, 5, 10, 15, 26);
        assertEquals(1, sink.frames.size());
        assertArrayEquals(data, sink.frames.get(0));

        byte[] small = blob(40, 9); // a single shard is simply duplicated
        feed(assembler, packetize(2, small, false, 0.2), 0);
        assertArrayEquals(small, sink.frames.get(1));
    }

    @Test public void repairsRandomLossesInEveryBlock() {
        Random random = new Random(42);
        Recorder sink = new Recorder();
        FecAssembler assembler = new FecAssembler(sink);
        for (int round = 0; round < 40; round++) {
            byte[] data = blob(20_000 + random.nextInt(400_000), round);
            List<byte[]> packets = packetize(round + 1, data, true, 0.25);
            int k = Math.max(1, (data.length + 1199) / 1200);
            int m = ReedSolomon.parityCount(k, 0.25);
            List<Integer> dropped = new ArrayList<>();
            for (int j = 0; j < ReedSolomon.blocks(k); j++) {
                int kj = ReedSolomon.blockData(k, j);
                int mj = ReedSolomon.blockParity(k, m, j);
                List<Integer> members = new ArrayList<>();
                for (int c = 0; c < kj; c++) members.add(ReedSolomon.blockDataStart(k, j) + c);
                for (int r = 0; r < mj; r++) members.add(ReedSolomon.blockParityStart(k, m, j) + r);
                java.util.Collections.shuffle(members, random);
                dropped.addAll(members.subList(0, random.nextInt(mj + 1)));
            }
            feed(assembler, packets, dropped.stream().mapToInt(Integer::intValue).toArray());
            assertEquals(round + 1, sink.frames.size());
            assertArrayEquals(data, sink.frames.get(round));
        }
        assertEquals(0, sink.losses);
    }

    @Test public void unrecoverableFrameWaitsForKeyframe() {
        Recorder sink = new Recorder();
        FecAssembler assembler = new FecAssembler(sink);
        feed(assembler, packetize(1, blob(3000, 1), true, 0.2));
        feed(assembler, packetize(2, blob(30_000, 2), false, 0.2), 0, 1, 2, 3, 4, 5); // 6 losses > 5 parity
        feed(assembler, packetize(3, blob(3000, 3), false, 0.2));                   // undecodable without 2
        assertEquals(1, sink.frames.size());
        assertTrue(sink.losses > 0);
        feed(assembler, packetize(4, blob(3000, 4), false, 0.2));
        assertEquals(1, sink.frames.size());
        feed(assembler, packetize(5, blob(3000, 5), true, 0.2));
        feed(assembler, packetize(6, blob(3000, 6), false, 0.2));
        assertEquals(3, sink.frames.size());
        assertArrayEquals(blob(3000, 6), sink.frames.get(2));
        assertTrue(assembler.takeStats()[2] >= 2);
    }

    @Test public void staleFrameTriggersLossOnIdle() {
        Recorder sink = new Recorder();
        FecAssembler assembler = new FecAssembler(sink);
        feed(assembler, packetize(1, blob(3000, 1), true, 0.2));
        feed(assembler, packetize(2, blob(30_000, 2), false, 0.2), 0, 1, 2, 3, 4, 5);
        assertEquals(0, sink.losses);
        assembler.onIdle(100_000_000L);
        assertEquals(1, sink.losses);
        feed(assembler, packetize(3, blob(3000, 3), true, 0.2));
        assertEquals(2, sink.frames.size());
        assembler.onIdle(200_000_000L);
        assertEquals(1, sink.losses);
    }
}
