package com.barcodetyper;

public class DataMatrixScanner {
    private static final int[][] DM_SYMBOLS = {
        {10,10, 1,1, 3, 5, 1}, {12,12, 1,1, 5, 7, 1}, {14,14, 1,1, 8, 10, 1},
        {16,16, 1,1, 12, 12, 1}, {18,18, 1,1, 18, 14, 1}, {20,20, 1,1, 22, 18, 1},
        {22,22, 1,1, 30, 20, 1}, {24,24, 1,1, 36, 24, 1}, {26,26, 1,1, 44, 28, 1},
        {32,32, 2,2, 62, 36, 1}, {36,36, 2,2, 86, 42, 1}, {40,40, 2,2, 114, 48, 1},
        {44,44, 2,2, 144, 56, 1}, {48,48, 2,2, 174, 68, 1}, {52,52, 2,2, 204, 84, 2}
    };

    private static final int[] dmExp = new int[512];
    private static final int[] dmLog = new int[256];
    private static boolean gfInit = false;

    private static void initGF() {
        if (gfInit) return;
        int v = 1;
        for (int i = 0; i < 255; i++) {
            dmExp[i] = dmExp[i + 255] = v;
            dmLog[v] = i;
            v <<= 1;
            if ((v & 0x100) != 0) v ^= 0x12D;
        }
        dmExp[510] = dmExp[0];
        gfInit = true;
    }

    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        initGF();
        int scale = (w > 960) ? (w / 720) : 1;
        int sw = w / scale, sh = h / scale;
        
        byte[] bin = new byte[sw * sh];
        int globalMid = 128;

        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int g = yPlane[(y * scale) * rowStride + (x * scale)] & 0xFF;
                if (g < globalMid - 20) bin[y * sw + x] = 1;
            }
        }

        int minX = sw, minY = sh, maxX = -1, maxY = -1;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                if (bin[y * sw + x] == 1) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX > minX + 20 && maxY > minY + 20) {
            return tryDecodeBox(bin, sw, sh, minX, minY, maxX, maxY);
        }
        return null;
    }

    private static String tryDecodeBox(byte[] bin, int w, int h, int x0, int y0, int x1, int y1) {
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        if (bw < 10 || bh < 10) return null;

        for (int[] sym : DM_SYMBOLS) {
            int srCnt = sym[0], scCnt = sym[1];
            float aspectBox = (float) bw / bh, aspectSym = (float) scCnt / srCnt;

            if (aspectBox < aspectSym * 0.65f || aspectBox > aspectSym * 1.45f) continue;
            float mw = (float) bw / scCnt, mh = (float) bh / srCnt;
            if (mw < 1.2f || mh < 1.2f) continue;

            byte[] grid = new byte[srCnt * scCnt];
            for (int r = 0; r < srCnt; r++) {
                for (int c = 0; c < scCnt; c++) {
                    int fx = x0 + (int)((c + 0.5f) * mw), fy = y0 + (int)((r + 0.5f) * mh);
                    if (fx >= 0 && fx < w && fy >= 0 && fy < h) grid[r * scCnt + c] = bin[fy * w + fx];
                }
            }

            int nrow = srCnt - 2 * sym[2], ncol = scCnt - 2 * sym[3];
            int[] placeMap = new int[nrow * ncol];
            buildUtahMap(placeMap, nrow, ncol, 4);
            
            byte[] cw = new byte[512];
            for (int dr = 0; dr < nrow; dr++) {
                int sr = 1 + dr + 2 * (dr / (nrow / sym[2]));
                for (int dc = 0; dc < ncol; dc++) {
                    int sc = 1 + dc + 2 * (dc / (ncol / sym[3]));
                    int val = placeMap[dr * ncol + dc];
                    if (val >= 0 && grid[sr * scCnt + sc] == 1) cw[val >> 3] |= (1 << (val & 7));
                }
            }

            if (verifyAndCorrectRS(cw, sym[4], sym[5])) return decodeDataMatrixStream(cw, sym[4]);
        }
        return null;
    }

    private static boolean verifyAndCorrectRS(byte[] cw, int dataCw, int eccCw) {
        int N = dataCw + eccCw;
        boolean hasErr = false;
        for (int j = 0; j < eccCw; j++) {
            int syn = 0, a_j = dmExp[j + 1];
            for (int i = 0; i < N; i++) syn = dmMul(syn, a_j) ^ (cw[i] & 0xFF);
            if (syn != 0) hasErr = true;
        }
        return !hasErr;
    }

    private static int dmMul(int a, int b) {
        return (a != 0 && b != 0) ? dmExp[dmLog[a] + dmLog[b]] : 0;
    }

    private static String decodeDataMatrixStream(byte[] cw, int dataCw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < dataCw) {
            int b = cw[i++] & 0xFF;
            if (b == 129) break;
            if (b >= 1 && b <= 128) out.append((char) (b - 1));
            else if (b >= 130 && b <= 229) {
                int pair = b - 130;
                out.append((char) ('0' + (pair / 10))).append((char) ('0' + (pair % 10)));
            }
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static void buildUtahMap(int[] map, int nrow, int ncol, int wrapOff) {
        for (int i = 0; i < map.length; i++) map[i] = -1;
        int cw = 0, r = 4, c = 0;
        while (r < nrow || c < ncol) {
            if (r == nrow && c == 0) { cw++; r -= 2; c += 2; continue; }
            do {
                if (r >= 0 && r < nrow && c >= 0 && c < ncol && map[r * ncol + c] == -1) dmUtah(map, r, c, nrow, ncol, cw++, wrapOff);
                r -= 2; c += 2;
            } while (r >= 0 && c < ncol);
            r += 1; c += 3;
            do {
                if (r >= 0 && r < nrow && c >= 0 && c < ncol && map[r * ncol + c] == -1) dmUtah(map, r, c, nrow, ncol, cw++, wrapOff);
                r += 2; c -= 2;
            } while (r < nrow && c >= 0);
            r += 3; c += 1;
        }
    }

    private static void dmUtah(int[] map, int r, int c, int nrow, int ncol, int cw, int wrapOff) {
        dmPlaceBit(map, r-2, c-2, nrow, ncol, cw, 7, wrapOff);
        dmPlaceBit(map, r-2, c-1, nrow, ncol, cw, 6, wrapOff);
        dmPlaceBit(map, r-1, c-2, nrow, ncol, cw, 5, wrapOff);
        dmPlaceBit(map, r-1, c-1, nrow, ncol, cw, 4, wrapOff);
        dmPlaceBit(map, r-1, c,   nrow, ncol, cw, 3, wrapOff);
        dmPlaceBit(map, r,   c-2, nrow, ncol, cw, 2, wrapOff);
        dmPlaceBit(map, r,   c-1, nrow, ncol, cw, 1, wrapOff);
        dmPlaceBit(map, r,   c,   nrow, ncol, cw, 0, wrapOff);
    }

    private static void dmPlaceBit(int[] map, int r, int c, int nrow, int ncol, int cwIdx, int bitShift, int wrapOff) {
        if (r < 0) { r += nrow; c += 4 - ((nrow + wrapOff) % 8); }
        if (c < 0) { c += ncol; r += 4 - ((ncol + wrapOff) % 8); }
        if (r >= 0 && r < nrow && c >= 0 && c < ncol) map[r * ncol + c] = (cwIdx << 3) | bitShift;
    }
}
