package com.fehlenfusion.immichscreensaver;

/**
 * Minimal QR Code encoder (ISO/IEC 18004): byte mode, error-correction level M,
 * versions 1-10 (up to 213 bytes), which is plenty for a setup URL.
 * Pure Java so it can be unit-tested on the JVM.
 */
final class QrCode {
    // Level M parameters, index = version.
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int MAX_VERSION = 10;

    final int version;
    final int size;
    private final boolean[][] modules;    // [y][x], true = dark
    private final boolean[][] isFunction; // [y][x]

    boolean get(int x, int y) {
        return modules[y][x];
    }

    static QrCode encode(byte[] data) {
        for (int v = 1; v <= MAX_VERSION; v++) {
            int countBits = v <= 9 ? 8 : 16;
            int capacityBits = dataCodewords(v) * 8;
            if (4 + countBits + data.length * 8 <= capacityBits) return new QrCode(v, data);
        }
        throw new IllegalArgumentException("data too long for QR version " + MAX_VERSION);
    }

    private QrCode(int ver, byte[] data) {
        version = ver;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        drawFunctionPatterns();
        byte[] all = addEccAndInterleave(buildDataCodewords(data));
        drawCodewords(all);

        int bestMask = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                bestMask = m;
            }
            applyMask(m); // undo (XOR)
        }
        applyMask(bestMask);
        drawFormatBits(bestMask);
    }

    // ------------------------------------------------------------ capacity

    private static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    // ------------------------------------------------------------ data

    private byte[] buildDataCodewords(byte[] data) {
        int capacity = dataCodewords(version);
        BitBuffer bb = new BitBuffer(capacity * 8);
        bb.append(0x4, 4); // byte mode
        bb.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.len));          // terminator
        bb.append(0, (8 - bb.len % 8) % 8);                         // byte align
        for (int pad = 0xEC; bb.len < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.toBytes();
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int rawCodewords = rawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortDataLen = rawCodewords / numBlocks - eccLen;

        byte[] divisor = rsDivisor(eccLen);
        byte[][] dataBlocks = new byte[numBlocks][];
        byte[][] eccBlocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int len = shortDataLen + (i < numShortBlocks ? 0 : 1);
            byte[] blk = new byte[len];
            System.arraycopy(data, k, blk, 0, len);
            k += len;
            dataBlocks[i] = blk;
            eccBlocks[i] = rsRemainder(blk, divisor);
        }

        byte[] result = new byte[rawCodewords];
        int idx = 0;
        for (int i = 0; i <= shortDataLen; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i < dataBlocks[j].length) result[idx++] = dataBlocks[j][i];
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int j = 0; j < numBlocks; j++) result[idx++] = eccBlocks[j][i];
        }
        return result;
    }

    // ------------------------------------------------------------ Reed-Solomon over GF(256)

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ------------------------------------------------------------ function patterns

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);

        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                boolean overlapsFinder = (i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0);
                if (!overlapsFinder) drawAlignment(pos[i], pos[j]);
            }
        }
        drawFormatBits(0); // reserve; real bits drawn after masking
        drawVersion();
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int numAlign = version / 7 + 2;
        int step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, p = size - 7; i >= 1; i--, p -= step) result[i] = p;
        return result;
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFunction(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = (0 << 3) | mask; // level M = 0b00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true); // dark module
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            setFunction(a, c, b);
            setFunction(c, a, b);
        }
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // ------------------------------------------------------------ data placement & masking

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Simplified penalty (runs, 2x2 blocks, dark balance). Any mask yields a valid code. */
    private long penalty() {
        long p = 0;
        for (int a = 0; a < size; a++) {
            int runRow = 1, runCol = 1;
            for (int b = 1; b < size; b++) {
                if (modules[a][b] == modules[a][b - 1]) {
                    if (++runRow == 5) p += 3; else if (runRow > 5) p++;
                } else runRow = 1;
                if (modules[b][a] == modules[b - 1][a]) {
                    if (++runCol == 5) p += 3; else if (runCol > 5) p++;
                } else runCol = 1;
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x + 1 < size && y + 1 < size) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10L;
    }

    // ------------------------------------------------------------ bits

    private static final class BitBuffer {
        final byte[] buf;
        int len;

        BitBuffer(int capacityBits) {
            buf = new byte[(capacityBits + 7) / 8];
        }

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--, len++) {
                if (((val >>> i) & 1) != 0) buf[len >>> 3] |= (byte) (0x80 >>> (len & 7));
            }
        }

        byte[] toBytes() {
            return buf;
        }
    }
}
