package io.github.lengsukq.opaldesk.daemon.stream;

import java.util.Arrays;

/**
 * Pure Java 11 Reed-Solomon erasure coding over GF(2^8) (primitive polynomial 0x11D)
 * using a systematic Cauchy generator matrix and Gauss-Jordan elimination decoding.
 * Zero third-party dependencies.
 */
public final class ReedSolomonFec {
    private static final int PRIMITIVE_POLY = 0x11D;
    private static final int FIELD_SIZE = 256;
    private static final int MAX_TOTAL_SHARDS = 255;

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[FIELD_SIZE];
    private static final byte[][] MUL_TABLE = new byte[FIELD_SIZE][FIELD_SIZE];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) {
                x ^= PRIMITIVE_POLY;
            }
        }
        for (int i = 255; i < 512; i++) {
            EXP[i] = EXP[i - 255];
        }
        for (int a = 0; a < FIELD_SIZE; a++) {
            for (int b = 0; b < FIELD_SIZE; b++) {
                MUL_TABLE[a][b] = (byte) ((a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]]);
            }
        }
    }

    private ReedSolomonFec() {}

    static int gfMul(int a, int b) {
        return MUL_TABLE[a & 0xFF][b & 0xFF] & 0xFF;
    }

    static int gfInv(int a) {
        int v = a & 0xFF;
        if (v == 0) {
            throw new ArithmeticException("Division by zero in GF(2^8)");
        }
        return EXP[255 - LOG[v]];
    }

    private static int cauchyCoeff(int parityRow, int dataCol, int parityCount) {
        return gfInv(parityRow ^ (parityCount + dataCol));
    }

    /**
     * Encodes {@code parityCount} Reed-Solomon parity shards from equal-length {@code dataShards}.
     */
    public static byte[][] encodeParity(byte[][] dataShards, int parityCount) {
        if (dataShards == null || dataShards.length == 0) {
            throw new IllegalArgumentException("dataShards must be non-empty");
        }
        if (parityCount < 0) {
            throw new IllegalArgumentException("parityCount must be >= 0");
        }
        int dataCount = dataShards.length;
        if (dataCount + parityCount > MAX_TOTAL_SHARDS) {
            throw new IllegalArgumentException("Total shards exceed GF(2^8) limit: " + (dataCount + parityCount));
        }
        if (dataShards[0] == null) {
            throw new IllegalArgumentException("dataShards[0] is null");
        }
        int shardLen = dataShards[0].length;
        for (int i = 1; i < dataCount; i++) {
            if (dataShards[i] == null || dataShards[i].length != shardLen) {
                throw new IllegalArgumentException("All data shards must be non-null and equal length");
            }
        }
        if (parityCount == 0) {
            return new byte[0][];
        }
        byte[][] parity = new byte[parityCount][shardLen];
        for (int p = 0; p < parityCount; p++) {
            byte[] dst = parity[p];
            for (int j = 0; j < dataCount; j++) {
                int coeff = cauchyCoeff(p, j, parityCount);
                byte[] mul = MUL_TABLE[coeff];
                byte[] src = dataShards[j];
                for (int b = 0; b < shardLen; b++) {
                    dst[b] ^= mul[src[b] & 0xFF];
                }
            }
        }
        return parity;
    }

    /**
     * Recovers all {@code dataCount} data shards from any {@code dataCount} present shards
     * among {@code shards[0 .. dataCount + parityCount - 1]}.
     */
    public static byte[][] decode(byte[][] shards, boolean[] present, int dataCount, int parityCount) {
        if (dataCount <= 0 || parityCount < 0 || dataCount + parityCount > MAX_TOTAL_SHARDS) {
            throw new IllegalArgumentException("Invalid shard counts: data=" + dataCount + " parity=" + parityCount);
        }
        int total = dataCount + parityCount;
        if (shards == null || shards.length < total || present == null || present.length < total) {
            throw new IllegalArgumentException("shards and present arrays must have length >= " + total);
        }

        int shardLen = -1;
        int[] selected = new int[dataCount];
        int found = 0;
        boolean allDataPresent = true;
        for (int i = 0; i < total; i++) {
            if (present[i]) {
                if (shards[i] == null) {
                    throw new IllegalArgumentException("Present shard " + i + " is null");
                }
                if (shardLen < 0) {
                    shardLen = shards[i].length;
                } else if (shards[i].length != shardLen) {
                    throw new IllegalArgumentException("Mismatched shard length at index " + i);
                }
                if (found < dataCount) {
                    selected[found++] = i;
                }
            } else if (i < dataCount) {
                allDataPresent = false;
            }
        }
        if (found < dataCount) {
            throw new IllegalArgumentException("Insufficient shards to decode: need " + dataCount + ", got " + found);
        }

        byte[][] out = new byte[dataCount][];
        if (allDataPresent) {
            for (int d = 0; d < dataCount; d++) {
                out[d] = Arrays.copyOf(shards[d], shardLen);
            }
            return out;
        }

        int[][] mat = new int[dataCount][dataCount];
        for (int r = 0; r < dataCount; r++) {
            int idx = selected[r];
            if (idx < dataCount) {
                mat[r][idx] = 1;
            } else {
                int p = idx - dataCount;
                for (int c = 0; c < dataCount; c++) {
                    mat[r][c] = cauchyCoeff(p, c, parityCount);
                }
            }
        }
        int[][] inv = invertMatrix(mat);

        for (int d = 0; d < dataCount; d++) {
            if (present[d]) {
                out[d] = Arrays.copyOf(shards[d], shardLen);
                continue;
            }
            byte[] dst = new byte[shardLen];
            for (int c = 0; c < dataCount; c++) {
                int coeff = inv[d][c];
                if (coeff == 0) continue;
                byte[] mul = MUL_TABLE[coeff];
                byte[] src = shards[selected[c]];
                for (int b = 0; b < shardLen; b++) {
                    dst[b] ^= mul[src[b] & 0xFF];
                }
            }
            out[d] = dst;
        }
        return out;
    }

    private static int[][] invertMatrix(int[][] matrix) {
        int n = matrix.length;
        int[][] aug = new int[n][2 * n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(matrix[r], 0, aug[r], 0, n);
            aug[r][n + r] = 1;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            while (pivot < n && aug[pivot][col] == 0) {
                pivot++;
            }
            if (pivot == n) {
                throw new IllegalStateException("Singular GF(2^8) decoding matrix");
            }
            if (pivot != col) {
                int[] tmp = aug[col];
                aug[col] = aug[pivot];
                aug[pivot] = tmp;
            }
            int invPivot = gfInv(aug[col][col]);
            for (int j = 0; j < 2 * n; j++) {
                aug[col][j] = gfMul(aug[col][j], invPivot);
            }
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                int factor = aug[r][col];
                if (factor == 0) continue;
                for (int j = 0; j < 2 * n; j++) {
                    aug[r][j] ^= gfMul(factor, aug[col][j]);
                }
            }
        }
        int[][] inv = new int[n][n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(aug[r], n, inv[r], 0, n);
        }
        return inv;
    }
}
