/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class DataMatrixScanner {

    private static class DMSymbolInfo {
        final int symR, symC, regR, regC, dataCw, eccCw, blocks;
        DMSymbolInfo(int symR, int symC, int regR, int regC, int dataCw, int eccCw, int blocks) {
            this.symR = symR; this.symC = symC; this.regR = regR; this.regC = regC;
            this.dataCw = dataCw; this.eccCw = eccCw; this.blocks = blocks;
        }
    }

    private static final DMSymbolInfo[] DM_SYMBOLS = {
        new DMSymbolInfo(10,10, 1,1,   3,  5, 1), new DMSymbolInfo(12,12, 1,1,   5,  7, 1),
        new DMSymbolInfo(14,14, 1,1,   8, 10, 1), new DMSymbolInfo(16,16, 1,1,  12, 12, 1),
        new DMSymbolInfo(18,18, 1,1,  18, 14, 1), new DMSymbolInfo(20,20, 1,1,  22, 18, 1),
        new DMSymbolInfo(22,22, 1,1,  30, 20, 1), new DMSymbolInfo(24,24, 1,1,  36, 24, 1),
        new DMSymbolInfo(26,26, 1,1,  44, 28, 1), new DMSymbolInfo(32,32, 2,2,  62, 36, 1),
        new DMSymbolInfo(36,36, 2,2,  86, 42, 1), new DMSymbolInfo(40,40, 2,2, 114, 48, 1),
        new DMSymbolInfo(44,44, 2,2, 144, 56, 1), new DMSymbolInfo(48,48, 2,2, 174, 68, 1),
        new DMSymbolInfo(52,52, 2,2, 204, 84, 2), new DMSymbolInfo( 8,18, 1,1,   5,  7, 1),
        new DMSymbolInfo( 8,32, 1,2,  10, 11, 1), new DMSymbolInfo(12,26, 1,1,  16, 14, 1),
        new DMSymbolInfo(12,36, 1,2,  22, 18, 1), new DMSymbolInfo(16,36, 1,2,  32, 24, 1),
        new DMSymbolInfo(16,48, 1,2,  49, 28, 1)
    };

    private static final int[] dmExp = new int[512];
    private static final int[] dmLog = new int[256];
    private static boolean gfInit = false;

    private static synchronized void initDataMatrixGF() {
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

    private static int dmMul(int a, int b) {
        return (a != 0 && b != 0) ? dmExp[dmLog[a] + dmLog[b]] : 0;
    }

    private static int dmInv(int a) {
        return a != 0 ? dmExp[255 - dmLog[a]] : 0;
    }

    private static boolean verifyAndCorrectRS(int[] cw, int dataCw, int eccCw) {
        initDataMatrixGF();
        int N = dataCw + eccCw;
        int[] S = new int[128];
        boolean hasErr = false;

        for (int j = 0; j < eccCw; j++) {
            int syn = 0, aJ = dmExp[j + 1];
            for (int i = 0; i < N; i++) syn = dmMul(syn, aJ) ^ cw[i];
            S[j] = syn;
            if (syn != 0) hasErr = true;
        }
        if (!hasErr) return true;

        int[] C = new int[128], B = new int[128];
        C[0] = 1; B[0] = 1;
        int L = 0, m = 1, b = 1;

        for (int n = 0; n < eccCw; n++) {
            int d = S[n];
            for (int i = 1; i <= L; i++) d ^= dmMul(C[i], S[n - i]);
            if (d == 0) {
                m++;
            } else if (2 * L <= n) {
                int[] T = java.util.Arrays.copyOf(C, 128);
                int coef = dmMul(d, dmInv(b));
                for (int i = m; i < 128; i++) C[i] ^= dmMul(coef, B[i - m]);
                L = n + 1 - L;
                System.arraycopy(T, 0, B, 0, 128);
                b = d;
                m = 1;
            } else {
                int coef = dmMul(d, dmInv(b));
                for (int i = m; i < 128; i++) C[i] ^= dmMul(coef, B[i - m]);
                m++;
            }
        }
        if (L > eccCw / 2 || L > 16) return false;

        int[] errPos = new int[32];
        int errCnt = 0;
        for (int i = 0; i < N; i++) {
            int p = N - 1 - i;
            int xInv = dmExp[(255 - (p % 255)) % 255], eval = 0, xPow = 1;
            for (int j = 0; j <= L; j++) {
                eval ^= dmMul(C[j], xPow);
                xPow = dmMul(xInv, xPow);
            }
            if (eval == 0 && errCnt < 32) errPos[errCnt++] = i;
        }
        if (errCnt != L) return false;

        int[] omega = new int[128];
        for (int i = 0; i < eccCw; i++) {
            for (int j = 0; j <= L && i + j < eccCw; j++) {
                omega[i + j] ^= dmMul(S[i], C[j]);
            }
        }

        for (int e = 0; e < errCnt; e++) {
            int idx = errPos[e], p = N - 1 - idx;
            int xInv = dmExp[(255 - (p % 255)) % 255], num = 0, xPow = 1;
            for (int j = 0; j < eccCw; j++) {
                num ^= dmMul(omega[j], xPow);
                xPow = dmMul(xPow, xInv);
            }
            int den = 0, xInv2 = dmMul(xInv, xInv);
            xPow = 1;
            for (int j = 1; j <= L; j += 2) {
                den ^= dmMul(C[j], xPow);
                xPow = dmMul(xPow, xInv2);
            }
            if (den == 0) return false;
            cw[idx] ^= dmMul(num, dmInv(den));
        }

        for (int j = 0; j < eccCw; j++) {
            int syn = 0, aJ = dmExp[j + 1];
            for (int i = 0; i < N; i++) syn = dmMul(syn, aJ) ^ cw[i];
            if (syn != 0) return false;
        }
        return true;
    }

    private static boolean verifyAndCorrectDMBlocks(int[] cw, int dataCw, int eccCw, int blocks) {
        if (blocks <= 1) return verifyAndCorrectRS(cw, dataCw, eccCw);

        int dPerBlk = dataCw / blocks;
        int ePerBlk = eccCw / blocks;
        int[] blk = new int[256];

        for (int b = 0; b < blocks; b++) {
            for (int i = 0; i < dPerBlk; i++) blk[i] = cw[i * blocks + b];
            for (int i = 0; i < ePerBlk; i++) blk[dPerBlk + i] = cw[dataCw + i * blocks + b];
            if (!verifyAndCorrectRS(blk, dPerBlk, ePerBlk)) return false;
            for (int i = 0; i < dPerBlk; i++) cw[i * blocks + b] = blk[i];
            for (int i = 0; i < ePerBlk; i++) cw[dataCw + i * blocks + b] = blk[dPerBlk + i];
        }
        return true;
    }

    private static void dmPlaceBit(int[] map, int r, int c, int nrow, int ncol, int cwIdx, int bitShift, int wrapOff) {
        if (r < 0) { r += nrow; c += 4 - ((nrow + wrapOff) % 8); }
        if (c < 0) { c += ncol; r += 4 - ((ncol + wrapOff) % 8); }
        if (r >= 0 && r < nrow && c >= 0 && c < ncol) {
            map[r * ncol + c] = (cwIdx << 3) | bitShift;
        }
    }

    private static void dmUtah(int[] map, int r, int c, int nrow, int ncol, int cw, int wrapOff) {
        dmPlaceBit(map, r - 2, c - 2, nrow, ncol, cw, 7, wrapOff);
        dmPlaceBit(map, r - 2, c - 1, nrow, ncol, cw, 6, wrapOff);
        dmPlaceBit(map, r - 1, c - 2, nrow, ncol, cw, 5, wrapOff);
        dmPlaceBit(map, r - 1, c - 1, nrow, ncol, cw, 4, wrapOff);
        dmPlaceBit(map, r - 1, c,     nrow, ncol, cw, 3, wrapOff);
        dmPlaceBit(map, r,     c - 2, nrow, ncol, cw, 2, wrapOff);
        dmPlaceBit(map, r,     c - 1, nrow, ncol, cw, 1, wrapOff);
        dmPlaceBit(map, r,     c,     nrow, ncol, cw, 0, wrapOff);
    }

    private static void buildUtahPlacementMap(int[] map, int nrow, int ncol, int wrapOff) {
        java.util.Arrays.fill(map, 0, nrow * ncol, -1);
        int cw = 0, r = 4, c = 0;
        while (r < nrow || c < ncol) {
            if (r == nrow && c == 0) {
                dmPlaceBit(map, nrow - 1, 0, nrow, ncol, cw, 7, wrapOff);
                dmPlaceBit(map, nrow - 1, 1, nrow, ncol, cw, 6, wrapOff);
                dmPlaceBit(map, nrow - 1, 2, nrow, ncol, cw, 5, wrapOff);
                dmPlaceBit(map, 0, ncol - 2, nrow, ncol, cw, 4, wrapOff);
                dmPlaceBit(map, 0, ncol - 1, nrow, ncol, cw, 3, wrapOff);
                dmPlaceBit(map, 1, ncol - 1, nrow, ncol, cw, 2, wrapOff);
                dmPlaceBit(map, 2, ncol - 1, nrow, ncol, cw, 1, wrapOff);
                dmPlaceBit(map, 3, ncol - 1, nrow, ncol, cw, 0, wrapOff);
                cw++; r -= 2; c += 2; continue;
            }
            if (r == nrow - 2 && c == 0 && ((ncol % 4) != 0)) {
                dmPlaceBit(map, nrow - 3, 0, nrow, ncol, cw, 7, wrapOff);
                dmPlaceBit(map, nrow - 2, 0, nrow, ncol, cw, 6, wrapOff);
                dmPlaceBit(map, nrow - 1, 0, nrow, ncol, cw, 5, wrapOff);
                dmPlaceBit(map, 0, ncol - 4, nrow, ncol, cw, 4, wrapOff);
                dmPlaceBit(map, 0, ncol - 3, nrow, ncol, cw, 3, wrapOff);
                dmPlaceBit(map, 0, ncol - 2, nrow, ncol, cw, 2, wrapOff);
                dmPlaceBit(map, 0, ncol - 1, nrow, ncol, cw, 1, wrapOff);
                dmPlaceBit(map, 1, ncol - 1, nrow, ncol, cw, 0, wrapOff);
                cw++; r -= 2; c += 2; continue;
            }
            if (r == nrow - 2 && c == 0 && ((ncol % 8) == 4)) {
                dmPlaceBit(map, nrow - 3, 0, nrow, ncol, cw, 7, wrapOff);
                dmPlaceBit(map, nrow - 2, 0, nrow, ncol, cw, 6, wrapOff);
                dmPlaceBit(map, nrow - 1, 0, nrow, ncol, cw, 5, wrapOff);
                dmPlaceBit(map, 0, ncol - 2, nrow, ncol, cw, 4, wrapOff);
                dmPlaceBit(map, 0, ncol - 1, nrow, ncol, cw, 3, wrapOff);
                dmPlaceBit(map, 1, ncol - 1, nrow, ncol, cw, 2, wrapOff);
                dmPlaceBit(map, 2, ncol - 1, nrow, ncol, cw, 1, wrapOff);
                dmPlaceBit(map, 3, ncol - 1, nrow, ncol, cw, 0, wrapOff);
                cw++; r -= 2; c += 2; continue;
            }
            if (r == nrow + 4 && c == 2 && ((ncol % 8) == 0)) {
                dmPlaceBit(map, nrow - 1, 0, nrow, ncol, cw, 7, wrapOff);
                dmPlaceBit(map, nrow - 1, ncol - 1, nrow, ncol, cw, 6, wrapOff);
                dmPlaceBit(map, 0, ncol - 3, nrow, ncol, cw, 5, wrapOff);
                dmPlaceBit(map, 0, ncol - 2, nrow, ncol, cw, 4, wrapOff);
                dmPlaceBit(map, 0, ncol - 1, nrow, ncol, cw, 3, wrapOff);
                dmPlaceBit(map, 1, ncol - 3, nrow, ncol, cw, 2, wrapOff);
                dmPlaceBit(map, 1, ncol - 2, nrow, ncol, cw, 1, wrapOff);
                dmPlaceBit(map, 1, ncol - 1, nrow, ncol, cw, 0, wrapOff);
                cw++; r -= 2; c += 2; continue;
            }
            do {
                if (r >= 0 && r < nrow && c >= 0 && c < ncol && map[r * ncol + c] == -1) {
                    dmUtah(map, r, c, nrow, ncol, cw++, wrapOff);
                }
                r -= 2; c += 2;
            } while (r >= 0 && c < ncol);
            r += 1; c += 3;
            do {
                if (r >= 0 && r < nrow && c >= 0 && c < ncol && map[r * ncol + c] == -1) {
                    dmUtah(map, r, c, nrow, ncol, cw++, wrapOff);
                }
                r += 2; c -= 2;
            } while (r < nrow && c >= 0);
            r += 3; c += 1;
        }

        if (map[nrow * ncol - 1] == -1) {
            map[nrow * ncol - 1] = -2;
            map[nrow * ncol - ncol - 2] = -2;
        }
    }

    private static void decodeC40Text(int[] cw, int[] idx, int dataCw, boolean isText, StringBuilder out) {
        int shift = 0;
        final String s2 = "!\"#$%&'()*+,-./:;<=>?@[\\]^_";
        while (idx[0] < dataCw && out.length() < 1024) {
            int b1 = cw[idx[0]++];
            if (b1 == 254 || idx[0] >= dataCw) break;
            int b2 = cw[idx[0]++];
            int v = (b1 << 8) + b2 - 1;
            int[] c = { v / 1600, (v / 40) % 40, v % 40 };
            for (int i = 0; i < 3 && out.length() < 1024; i++) {
                int val = c[i];
                if (shift == 0) {
                    if (val <= 2) shift = val + 1;
                    else if (val == 3) out.append(' ');
                    else if (val <= 13) out.append((char) ('0' + (val - 4)));
                    else if (val <= 39) out.append((char) ((isText ? 'a' : 'A') + (val - 14)));
                } else if (shift == 1) {
                    out.append((char) val); shift = 0;
                } else if (shift == 2) {
                    if (val < s2.length()) out.append(s2.charAt(val));
                    shift = 0;
                } else if (shift == 3) {
                    if (val == 0) out.append('`');
                    else if (val <= 26) out.append((char) ((isText ? 'A' : 'a') + (val - 1)));
                    shift = 0;
                }
            }
        }
    }

    private static String decodeDataMatrixStream(int[] cw, int dataCw, boolean allowEmpty) {
        if (allowEmpty && cw[0] == 129) return "";
        StringBuilder out = new StringBuilder();
        int i = 0, upper = 0;
        int[] idxWrapper = new int[1];

        while (i < dataCw && out.length() < 1024) {
            int b = cw[i++];
            if (b == 129) break;
            else if (b >= 1 && b <= 128) {
                char ch = (char) (b - 1 + (upper != 0 ? 128 : 0));
                upper = 0;
                if (ch >= 32 && ch <= 126) out.append(ch);
            } else if (b >= 130 && b <= 229) {
                int pair = b - 130;
                out.append((char) ('0' + (pair / 10)));
                out.append((char) ('0' + (pair % 10)));
            } else if (b == 230) {
                idxWrapper[0] = i;
                decodeC40Text(cw, idxWrapper, dataCw, false, out);
                i = idxWrapper[0];
            } else if (b == 239) {
                idxWrapper[0] = i;
                decodeC40Text(cw, idxWrapper, dataCw, true, out);
                i = idxWrapper[0];
            } else if (b == 235) {
                upper = 1;
            } else if (b == 231 && i < dataCw) {
                int d1 = cw[i] - (((149 * (i + 1)) % 255) + 1);
                if (d1 < 0) d1 += 256;
                i++;
                int count = (d1 == 0) ? (dataCw - i) : d1;
                for (int k = 0; k < count && i < dataCw && out.length() < 1024; k++, i++) {
                    int ch = cw[i] - (((149 * (i + 1)) % 255) + 1);
                    if (ch < 0) ch += 256;
                    if (ch >= 32 && ch <= 126) out.append((char) ch);
                }
            }
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static int sampleModuleBit(byte[] bin, int w, int h, float fx, float fy, float mw, float mh) {
        int x0 = (int) (fx - mw * 0.25f), x1 = (int) (fx + mw * 0.25f);
        int y0 = (int) (fy - mh * 0.25f), y1 = (int) (fy + mh * 0.25f);
        if (x0 < 0) x0 = 0; if (x1 >= w) x1 = w - 1;
        if (y0 < 0) y0 = 0; if (y1 >= h) y1 = h - 1;
        int dark = 0, total = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * w;
            for (int x = x0; x <= x1; x++) {
                dark += bin[row + x];
                total++;
            }
        }
        return (total > 0) ? ((dark * 2 >= total) ? 1 : 0) : bin[((int) fy) * w + ((int) fx)];
    }

    private static boolean tightenBinaryBox(byte[] bin, int w, int h, int[] inBox, int[] outBox) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        int x0 = Math.max(0, inBox[0]), y0 = Math.max(0, inBox[1]);
        int x1 = Math.min(w - 1, inBox[2]), y1 = Math.min(h - 1, inBox[3]);

        for (int y = y0; y <= y1; y++) {
            int row = y * w;
            for (int x = x0; x <= x1; x++) {
                if (bin[row + x] != 0) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX - minX < 8 || maxY - minY < 8) return false;
        outBox[0] = minX; outBox[1] = minY; outBox[2] = maxX; outBox[3] = maxY;
        return true;
    }

    private static String tryDecodeCandidateBoxEx(byte[] bin, int w, int h, int[] box, boolean allowEmpty) {
        int bw = box[2] - box[0] + 1, bh = box[3] - box[1] + 1;
        if (bw < 10 || bh < 10) return null;

        int[] placeMap = new int[64 * 64];
        byte[] grid = new byte[64 * 64];

        for (DMSymbolInfo sym : DM_SYMBOLS) {
            int srCnt = sym.symR, scCnt = sym.symC;
            float aspectBox = (float) bw / (float) bh;

            for (int rot = 0; rot < 4; rot++) {
                int physR = (rot % 2 == 0) ? srCnt : scCnt;
                int physC = (rot % 2 == 0) ? scCnt : srCnt;
                float aspectSym = (float) physC / (float) physR;
                if (aspectBox < aspectSym * 0.65f || aspectBox > aspectSym * 1.45f) continue;

                float mw = (float) bw / (float) physC, mh = (float) bh / (float) physR;
                if (mw < 1.2f || mh < 1.2f) continue;

                for (int r = 0; r < srCnt; r++) {
                    for (int c = 0; c < scCnt; c++) {
                        int pr = r, pc = c;
                        if (rot == 1)      { pr = c; pc = srCnt - 1 - r; }
                        else if (rot == 2) { pr = srCnt - 1 - r; pc = scCnt - 1 - c; }
                        else if (rot == 3) { pr = scCnt - 1 - c; pc = r; }

                        float fx = box[0] + (pc + 0.5f) * mw;
                        float fy = box[1] + (pr + 0.5f) * mh;
                        grid[r * scCnt + c] = (byte) sampleModuleBit(bin, w, h, fx, fy, mw, mh);
                    }
                }

                int borderOk = 0, borderTot = 2 * (srCnt + scCnt);
                for (int c = 0; c < scCnt; c++) {
                    if (grid[(srCnt - 1) * scCnt + c] == 1) borderOk++;
                    if (grid[c] == ((c % 2 == 0) ? 1 : 0)) borderOk++;
                }
                for (int r = 0; r < srCnt; r++) {
                    if (grid[r * scCnt] == 1) borderOk++;
                    if (grid[r * scCnt + (scCnt - 1)] == ((r % 2 == 1) ? 1 : 0)) borderOk++;
                }
                if (borderOk * 100 < borderTot * 78) continue;

                int regR = sym.regR, regC = sym.regC;
                int nrow = srCnt - 2 * regR, ncol = scCnt - 2 * regC;
                int subR = nrow / regR, subC = ncol / regC;

                final int[] wrapOffsets = { 3, 4 };
                for (int wrapOff : wrapOffsets) {
                    buildUtahPlacementMap(placeMap, nrow, ncol, wrapOff);
                    int[] cw = new int[512];

                    for (int dr = 0; dr < nrow; dr++) {
                        int sr = 1 + dr + 2 * (dr / subR);
                        for (int dc = 0; dc < ncol; dc++) {
                            int sc = 1 + dc + 2 * (dc / subC);
                            int val = placeMap[dr * ncol + dc];
                            if (val >= 0 && grid[sr * scCnt + sc] != 0) {
                                cw[val >> 3] |= (1 << (val & 7));
                            }
                        }
                    }

                    if (verifyAndCorrectDMBlocks(cw, sym.dataCw, sym.eccCw, sym.blocks)) {
                        String decoded = decodeDataMatrixStream(cw, sym.dataCw, allowEmpty);
                        if (decoded != null) return decoded;
                    }
                }
            }
        }
        return null;
    }

    private static
