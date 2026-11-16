package app.screeaner;

import java.util.ArrayList;

/**
 * Rebuilds video frames from UDP datagrams and repairs losses with the
 * interleaved XOR parity the Mac adds to every frame.
 *
 * Datagram: [frameId u32][index u16][k u16][m u16][flags u8][version u8][frameSize u32][shard].
 * A frame is split into k equal data shards (the last one zero padded) plus
 * m parity shards; parity j is the XOR of every data shard i with i % m == j.
 * One lost shard per group can be rebuilt, so a burst of up to m consecutive
 * losses is fully recovered.
 *
 * Frames are only delivered in order; when one is lost for good, delivery
 * pauses until the next keyframe and {@link Sink#onLoss()} asks for it.
 */
final class FecAssembler {
    interface Sink {
        /** blob = [pts u64][encodeMicros u32][Annex-B access unit]. */
        void onFrame(byte[] blob, int length, boolean key);

        void onLoss();
    }

    static final int HEADER = 16;
    static final int VERSION = 1;
    private static final int MAX_SHARD = 1400;
    private static final int MAX_FRAME = 8 * 1024 * 1024;
    private static final int MAX_PENDING = 8;
    private static final long STALE_NANOS = 60_000_000L;

    private static final class Frame {
        int id, k, m, shard, size, received;
        boolean key;
        byte[] data;
        boolean[] have;
        int[] missing;
        long firstNanos;
    }

    private final Sink sink;
    private final ArrayList<Frame> pending = new ArrayList<>();
    private boolean started;
    private int nextExpected;
    private boolean waitingKey = true;
    private boolean lossPending;
    private int packetsExpected, packetsReceived, framesLost;

    FecAssembler(Sink sink) {
        this.sink = sink;
    }

    synchronized void onPacket(byte[] buf, int length, long nowNanos) {
        if (length <= HEADER || (buf[11] & 0xFF) != VERSION) return;
        int id = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16) | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int index = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
        int k = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        int m = ((buf[8] & 0xFF) << 8) | (buf[9] & 0xFF);
        boolean key = (buf[10] & 1) != 0;
        int size = ((buf[12] & 0xFF) << 24) | ((buf[13] & 0xFF) << 16) | ((buf[14] & 0xFF) << 8) | (buf[15] & 0xFF);
        int shard = length - HEADER;
        if (k < 1 || m < 1 || index >= k + m || size < 1 || size > MAX_FRAME || shard > MAX_SHARD) return;
        if (shard != (size + k - 1) / k) return;

        packetsReceived++;
        if (started && id - nextExpected < 0) return; // late packet of a finished frame

        Frame frame = null;
        for (Frame candidate : pending) {
            if (candidate.id == id) {
                frame = candidate;
                break;
            }
        }
        if (frame == null) {
            if (pending.size() >= MAX_PENDING) abandon(pending.get(0));
            frame = new Frame();
            frame.id = id;
            frame.k = k;
            frame.m = m;
            frame.shard = shard;
            frame.size = size;
            frame.key = key;
            frame.data = new byte[(k + m) * shard];
            frame.have = new boolean[k + m];
            frame.missing = new int[m];
            for (int i = 0; i < k; i++) frame.missing[i % m]++;
            frame.firstNanos = nowNanos;
            pending.add(frame);
        } else if (frame.k != k || frame.m != m || frame.shard != shard) {
            return;
        }
        if (frame.have[index]) return;
        System.arraycopy(buf, HEADER, frame.data, index * shard, shard);
        frame.have[index] = true;
        frame.received++;
        if (index < k) frame.missing[index % m]--;

        if (isRecoverable(frame)) finish(frame);
    }

    /** Call regularly (e.g. on socket timeout) so stalled frames are detected. */
    synchronized void onIdle(long nowNanos) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Frame frame = pending.get(i);
            if (nowNanos - frame.firstNanos > STALE_NANOS) {
                abandon(frame);
                if (!started || frame.id - nextExpected >= 0) nextExpected = frame.id + 1;
                started = true;
                waitingKey = true;
                lossPending = true;
            }
        }
        if (lossPending) sink.onLoss();
    }

    /** Returns {packetsExpected, packetsLost, framesLost} since the last call. */
    synchronized int[] takeStats() {
        int[] stats = {packetsExpected, Math.max(0, packetsExpected - packetsReceived), framesLost};
        packetsExpected = 0;
        packetsReceived = 0;
        framesLost = 0;
        return stats;
    }

    private static boolean isRecoverable(Frame frame) {
        for (int j = 0; j < frame.m; j++) {
            if (frame.missing[j] == 0) continue;
            if (frame.missing[j] == 1 && frame.have[frame.k + j]) continue;
            return false;
        }
        return true;
    }

    private void finish(Frame frame) {
        repair(frame);
        pending.remove(frame);
        packetsExpected += frame.k + frame.m;

        boolean inOrder = started && !waitingKey && frame.id == nextExpected;
        if (frame.key || inOrder) {
            if (started && frame.id - nextExpected > 0) framesLost += frame.id - nextExpected;
            abandonOlderThan(frame.id);
            started = true;
            nextExpected = frame.id + 1;
            waitingKey = false;
            lossPending = false;
            sink.onFrame(frame.data, frame.size, frame.key);
        } else {
            // An earlier frame is missing, so this one cannot be decoded.
            framesLost++;
            abandonOlderThan(frame.id);
            if (!started || frame.id - nextExpected >= 0) nextExpected = frame.id + 1;
            started = true;
            waitingKey = true;
            lossPending = true;
            sink.onLoss();
        }
    }

    private static void repair(Frame frame) {
        byte[] data = frame.data;
        int shard = frame.shard;
        for (int j = 0; j < frame.m; j++) {
            if (frame.missing[j] != 1) continue;
            int lost = -1;
            for (int i = j; i < frame.k; i += frame.m) {
                if (!frame.have[i]) {
                    lost = i;
                    break;
                }
            }
            if (lost < 0) continue;
            int target = lost * shard;
            System.arraycopy(data, (frame.k + j) * shard, data, target, shard);
            for (int i = j; i < frame.k; i += frame.m) {
                if (i == lost) continue;
                int source = i * shard;
                for (int b = 0; b < shard; b++) data[target + b] ^= data[source + b];
            }
            frame.have[lost] = true;
            frame.missing[j] = 0;
        }
    }

    private void abandonOlderThan(int id) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Frame frame = pending.get(i);
            if (frame.id - id < 0) abandon(frame);
        }
    }

    private void abandon(Frame frame) {
        pending.remove(frame);
        packetsExpected += frame.k + frame.m;
        framesLost++;
    }
}
