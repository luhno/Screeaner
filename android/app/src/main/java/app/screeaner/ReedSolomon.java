package app.screeaner;

import java.util.Arrays;

/**
 * Systematic Reed-Solomon erasure code over GF(256) with a Cauchy matrix,
 * used by the UDP video FEC. Must stay bit-identical to
 * mac/Sources/ScreeanerCore/ReedSolomon.swift (both test suites pin the same digest).
 *
 * A frame of k data shards is split into blocks of at most 32 data shards;
 * each block gets its own parity shards and can rebuild any of its data
 * shards as long as it received at least as many shards as it has data.
 * Shards are laid out flat: shard i lives at [i * shard, (i + 1) * shard).
 */
final class ReedSolomon {
    static final int MAX_BLOCK_DATA = 32;

    private static final byte[] EXP = new byte[512];
    private static final int[] LOG = new int[256];
    /** MUL[(a << 8) | b] = a * b in GF(256). */
    static final byte[] MUL = new byte[256 * 256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = (byte) x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
        for (int a = 1; a < 256; a++) {
            for (int b = 1; b < 256; b++) MUL[(a << 8) | b] = EXP[LOG[a] + LOG[b]];
        }
    }

    private ReedSolomon() {}

    static int mul(int a, int b) {
        return MUL[(a << 8) | b] & 0xFF;
    }

    static int inv(int a) {
        return EXP[255 - LOG[a]] & 0xFF;
    }

    /** Coefficient of data shard c in parity shard r of a block with m parity shards. */
    static int coefficient(int r, int c, int m) {
        return inv(r ^ (m + c));
    }

    static int blocks(int k) {
        return (k + MAX_BLOCK_DATA - 1) / MAX_BLOCK_DATA;
    }

    static int blockData(int k, int j) {
        int b = blocks(k);
        return k / b + (j < k % b ? 1 : 0);
    }

    static int blockDataStart(int k, int j) {
        int b = blocks(k);
        return j * (k / b) + Math.min(j, k % b);
    }

    static int blockParity(int k, int m, int j) {
        int b = blocks(k);
        return m / b + (j < m % b ? 1 : 0);
    }

    static int blockParityStart(int k, int m, int j) {
        int b = blocks(k);
        return k + j * (m / b) + Math.min(j, m % b);
    }

    /** Parity shards to send for k data shards: at least one per block, at most k. */
    static int parityCount(int k, double redundancy) {
        return Math.min(k, Math.max(blocks(k), (int) Math.ceil(k * redundancy)));
    }

    /** Shard indexes in send order: blocks interleaved so a burst of losses is spread out. */
    static int[] sendOrder(int k, int m) {
        int[] order = new int[k + m];
        int b = blocks(k);
        int longest = 0;
        for (int j = 0; j < b; j++) longest = Math.max(longest, blockData(k, j) + blockParity(k, m, j));
        int n = 0;
        for (int t = 0; t < longest; t++) {
            for (int j = 0; j < b; j++) {
                int kj = blockData(k, j);
                int mj = blockParity(k, m, j);
                if (t < kj) order[n++] = blockDataStart(k, j) + t;
                else if (t < kj + mj) order[n++] = blockParityStart(k, m, j) + (t - kj);
            }
        }
        return order;
    }

    /** Computes every parity shard from the data shards already in place. */
    static void encode(byte[] shards, int shard, int k, int m) {
        for (int j = 0; j < blocks(k); j++) {
            int kj = blockData(k, j);
            int ds = blockDataStart(k, j);
            int mj = blockParity(k, m, j);
            int ps = blockParityStart(k, m, j);
            for (int r = 0; r < mj; r++) {
                int out = (ps + r) * shard;
                Arrays.fill(shards, out, out + shard, (byte) 0);
                for (int c = 0; c < kj; c++) {
                    int row = coefficient(r, c, mj) << 8;
                    int in = (ds + c) * shard;
                    for (int i = 0; i < shard; i++) shards[out + i] ^= MUL[row | (shards[in + i] & 0xFF)];
                }
            }
        }
    }

    /** Shards of block j received so far. */
    static int received(boolean[] have, int k, int m, int j) {
        int count = 0;
        int ds = blockDataStart(k, j);
        for (int c = 0; c < blockData(k, j); c++) if (have[ds + c]) count++;
        int ps = blockParityStart(k, m, j);
        for (int r = 0; r < blockParity(k, m, j); r++) if (have[ps + r]) count++;
        return count;
    }

    /**
     * Rebuilds the missing data shards of block j in place and marks them in
     * {@code have}. Returns false when the block did not receive enough shards.
     */
    static boolean decodeBlock(byte[] shards, int shard, boolean[] have, int k, int m, int j) {
        int kj = blockData(k, j);
        int ds = blockDataStart(k, j);
        int mj = blockParity(k, m, j);
        int ps = blockParityStart(k, m, j);

        int[] missing = new int[kj];
        int e = 0;
        for (int c = 0; c < kj; c++) if (!have[ds + c]) missing[e++] = c;
        if (e == 0) return true;
        int[] rows = new int[e];
        int found = 0;
        for (int r = 0; r < mj && found < e; r++) if (have[ps + r]) rows[found++] = r;
        if (found < e) return false;

        int[][] a = new int[e][e];
        byte[][] rhs = new byte[e][];
        for (int i = 0; i < e; i++) {
            int r = rows[i];
            byte[] value = Arrays.copyOfRange(shards, (ps + r) * shard, (ps + r + 1) * shard);
            for (int c = 0; c < kj; c++) {
                if (!have[ds + c]) continue;
                int row = coefficient(r, c, mj) << 8;
                int in = (ds + c) * shard;
                for (int b = 0; b < shard; b++) value[b] ^= MUL[row | (shards[in + b] & 0xFF)];
            }
            rhs[i] = value;
            for (int t = 0; t < e; t++) a[i][t] = coefficient(r, missing[t], mj);
        }

        // Gauss-Jordan elimination; Cauchy submatrices are always invertible.
        for (int col = 0; col < e; col++) {
            int pivot = col;
            while (pivot < e && a[pivot][col] == 0) pivot++;
            if (pivot == e) return false;
            int[] tmpRow = a[pivot]; a[pivot] = a[col]; a[col] = tmpRow;
            byte[] tmpRhs = rhs[pivot]; rhs[pivot] = rhs[col]; rhs[col] = tmpRhs;

            int scale = inv(a[col][col]);
            if (scale != 1) {
                for (int t = 0; t < e; t++) a[col][t] = mul(a[col][t], scale);
                int row = scale << 8;
                byte[] v = rhs[col];
                for (int b = 0; b < shard; b++) v[b] = MUL[row | (v[b] & 0xFF)];
            }
            for (int i = 0; i < e; i++) {
                int factor = a[i][col];
                if (i == col || factor == 0) continue;
                for (int t = 0; t < e; t++) a[i][t] ^= mul(factor, a[col][t]);
                int row = factor << 8;
                byte[] target = rhs[i];
                byte[] source = rhs[col];
                for (int b = 0; b < shard; b++) target[b] ^= MUL[row | (source[b] & 0xFF)];
            }
        }
        for (int t = 0; t < e; t++) {
            System.arraycopy(rhs[t], 0, shards, (ds + missing[t]) * shard, shard);
            have[ds + missing[t]] = true;
        }
        return true;
    }
}
