/* ============================================================================
 * Android Barcode Typer
 *
 * Compile:
 *   Mac / Linux:
 *   ./gradlew assembleDebug
 *   
 *   Windows:
 *   gradlew.bat assembleDebug
 *
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import java.util.ArrayList;
import java.util.List;

public class QRScanner {

    // Galois Field tables for Reed-Solomon
    private static final int[] expTable = new int[512];
    private static final int[] logTable = new int[256];
    
    private static final int[] FORMAT_INFO_DECODE = {
        0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0,
        0x77C4, 0x72F3, 0x7DAA, 0x789D, 0x662F, 0x6318, 0x6C41, 0x6976,
        0x1689, 0x13BE, 0x1CE7, 0x19D0, 0x0762, 0x0255, 0x0D0C, 0x083B,
        0x355F, 0x3068, 0x3F31, 0x3A06, 0x24B4, 0x2183, 0x2EDA, 0x2BED
    };

    // Expanded ECC tables to support Version 1 through Version 40
    private static final int[][] TOT_ECC = {
        {7,10,13,17}, {10,16,22,28}, {15,26,36,44}, {20,36,52,64}, {26,48,72,88},
        {36,64,96,112}, {40,72,108,130}, {48,88,132,156}, {60,110,160,192}, {72,130,192,224},
        {80,150,224,264}, {96,176,260,308}, {104,198,288,352}, {120,216,320,384}, {132,240,360,432},
        {144,280,408,480}, {168,308,448,532}, {180,338,504,588}, {196,364,546,650}, {224,416,600,700},
        {224,442,644,750}, {252,476,690,816}, {270,504,750,900}, {300,560,810,960}, {312,588,870,1050},
        {336,644,952,1110}, {360,700,1020,1200}, {390,728,1050,1260}, {420,784,1140,1350}, {450,812,1200,1440},
        {480,868,1290,1530}, {510,924,1350,1620}, {540,980,1440,1710}, {570,1036,1530,1800}, {570,1064,1590,1890},
        {600,1120,1680,1980}, {630,1204,1770,2100}, {660,1260,1860,2220}, {720,1316,1950,2310}, {750,1372,2040,2430}
    };
    
    private static final int[][] NUM_BLK = {
        {1,1,1,1}, {1,1,1,1}, {1,1,2,2}, {1,2,2,4}, {1,2,4,4},
        {2,4,4,4}, {2,4,6,5}, {2,4,6,6}, {2,5,8,8}, {4,5,8,8},
        {4,5,8,11}, {4,8,10,11}, {4,9,12,16}, {4,9,16,16}, {6,10,12,18},
        {6,10,17,16}, {6,11,16,19}, {6,13,18,21}, {7,14,21,25}, {8,16,20,25},
        {8,17,23,25}, {9,17,23,34}, {9,18,25,30}, {10,20,27,32}, {12,21,29,35},
        {12,23,34,37}, {12,25,34,40}, {13,26,35,42}, {14,28,38,45}, {15,29,40,48},
        {16,31,43,51}, {17,33,45,54}, {18,35,48,57}, {19,37,51,60}, {19,38,53,63},
        {20,40,56,66}, {21,43,59,70}, {22,45,62,74}, {24,47,65,77}, {25,49,68,81}
    };

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            expTable[i] = expTable[i + 255] = x;
            logTable[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        expTable[510] = expTable[0];
    }

    private static int gfMul(int a, int b) { return (a == 0 || b == 0) ? 0 : expTable[logTable[a] + logTable[b]]; }
    private static int gfInv(int a) { return expTable[255 - logTable[a]]; }

    // --- Primary Execution Shell ---
    public static String scanBuffer(byte[] yPlane, int w, int h, int rowStride) {
        try {
            return new Decoder(yPlane, w, h, rowStride).decode();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Object-Oriented QR Decoder ---
    private static class Decoder {
        private final byte[] yPlane;
        private final int w, h, stride;
        private byte[] bin;
        private int dim, version;

        public Decoder(byte[] yPlane, int w, int h, int stride) {
            this.yPlane = yPlane;
            this.w = w;
            this.h = h;
            this.stride = stride;
            this.bin = new byte[w * h];
        }

        public String decode() {
            binarize();
            Finder[] finders = locateFinders();
            if (finders == null) return null;

            int estimatedDim = estimateDimension(finders);
            if (estimatedDim < 21) return null;

            int[] offsets = {0, 4, -4, 8, -8, 12, -12};
            
            for (int offset : offsets) {
                this.dim = estimatedDim + offset;
                if (this.dim < 21 || this.dim > 177) continue;
                this.version = (this.dim - 17) / 4;

                byte[] grid = extractGrid(finders, this.dim);
                if (grid == null) continue;

                byte[] rawData = unmaskAndRead(grid);
                if (rawData == null) continue;

                byte[] corrected = errorCorrect(rawData);
                if (corrected == null) continue;

                String result = parseMessage(corrected);
                if (result != null) return result;
            }
            return null;
        }

        private void binarize() {
            int rad = Math.max(12, Math.min(w, h) / 8);
            long[] intImg = new long[(w + 1) * (h + 1)];

            for (int y = 0; y < h; y++) {
                long rowSum = 0;
                int srcRow = y * stride;
                for (int x = 0; x < w; x++) {
                    rowSum += (yPlane[srcRow + x] & 0xFF);
                    intImg[(y + 1) * (w + 1) + (x + 1)] = intImg[y * (w + 1) + (x + 1)] + rowSum;
                }
            }

            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - rad), y1 = Math.min(h - 1, y + rad);
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int x0 = Math.max(0, x - rad), x1 = Math.min(w - 1, x + rad);
                    long area = (long)(x1 - x0 + 1) * (y1 - y0 + 1);
                    long sum = intImg[(y1 + 1) * (w + 1) + (x1 + 1)] - intImg[y0 * (w + 1) + (x1 + 1)]
                             - intImg[(y1 + 1) * (w + 1) + x0] + intImg[y0 * (w + 1) + x0];
                    long mean = sum / area;
                    if ((yPlane[y * stride + x] & 0xFF) < mean - (mean >> 4)) {
                        bin[row + x] = 1;
                    }
                }
            }
        }

        private void handlePossibleCenter(int[] state, int y, int x, List<Finder> finders) {
            float cx = x - state[4] - state[3] - state[2] / 2.0f;
            float cy = crossCheckVertical((int) (cx + 0.5f), y, state[2] * 2);
            if (!Float.isNaN(cy)) {
                float finalX = crossCheckHorizontal((int) (cx + 0.5f), (int) (cy + 0.5f), state[2] * 2);
                if (!Float.isNaN(finalX)) {
                    float modSize = (state[0] + state[1] + state[2] + state[3] + state[4]) / 7.0f;
                    mergeFinder(finders, finalX, cy, modSize);
                }
            }
        }

        private Finder[] locateFinders() {
            List<Finder> finders = new ArrayList<>();
            int[] state = new int[5];

            for (int y = 3; y < h - 3; y += 2) {
                state[0] = state[1] = state[2] = state[3] = state[4] = 0;
                int currentState = 0;
                int row = y * w;

                for (int x = 0; x < w; x++) {
                    if (bin[row + x] != 0) {
                        if ((currentState & 1) == 1) currentState++;
                        state[currentState]++;
                    } else {
                        if ((currentState & 1) == 0) {
                            if (currentState == 4) {
                                if (checkRatio(state)) handlePossibleCenter(state, y, x, finders);
                                state[0] = state[2]; state[1] = state[3]; state[2] = state[4];
                                state[3] = 1; state[4] = 0; currentState = 3;
                            } else {
                                currentState++; state[currentState]++;
                            }
                        } else {
                            state[currentState]++;
                        }
                    }
                }
                
                if (currentState == 4 && checkRatio(state)) handlePossibleCenter(state, y, w, finders);
            }

            if (finders.size() < 3) return null;
            finders.sort((a, b) -> Integer.compare(b.count, a.count));
            Finder[] top3 = { finders.get(0), finders.get(1), finders.get(2) };

            float d01 = distSq(top3[0], top3[1]);
            float d12 = distSq(top3[1], top3[2]);
            float d02 = distSq(top3[0], top3[2]);

            Finder tl, tr, bl;
            if (d01 >= d12 && d01 >= d02) { tl = top3[2]; tr = top3[0]; bl = top3[1]; }
            else if (d02 >= d01 && d02 >= d12) { tl = top3[1]; tr = top3[0]; bl = top3[2]; }
            else { tl = top3[0]; tr = top3[1]; bl = top3[2]; }

            if ((tr.x - tl.x) * (bl.y - tl.y) - (tr.y - tl.y) * (bl.x - tl.x) < 0) {
                Finder temp = tr; tr = bl; bl = temp;
            }
            return new Finder[] { tl, tr, bl };
        }

        private boolean checkRatio(int[] state) {
            int total = 0;
            for (int i = 0; i < 5; i++) {
                if (state[i] == 0) return false;
                total += state[i];
            }
            if (total < 7) return false;
            int mod = (total << 8) / 7;
            int maxVar = Math.max(mod, 256); 
            return Math.abs(mod - (state[0] << 8)) < maxVar && Math.abs(mod - (state[1] << 8)) < maxVar &&
                   Math.abs(3 * mod - (state[2] << 8)) < 3 * maxVar && Math.abs(mod - (state[3] << 8)) < maxVar &&
                   Math.abs(mod - (state[4] << 8)) < maxVar;
        }

        private float crossCheckVertical(int startX, int startY, int maxCount) {
            if (startX < 0 || startX >= w) return Float.NaN;
            int[] state = new int[5];
            int i = startY;
            while (i >= 0 && bin[i * w + startX] != 0) { state[2]++; i--; }
            if (i < 0) return Float.NaN;
            while (i >= 0 && bin[i * w + startX] == 0 && state[1] <= maxCount) { state[1]++; i--; }
            if (i < 0 || state[1] > maxCount) return Float.NaN;
            while (i >= 0 && bin[i * w + startX] != 0 && state[0] <= maxCount) { state[0]++; i--; }
            if (state[0] > maxCount) return Float.NaN;

            i = startY + 1;
            while (i < h && bin[i * w + startX] != 0) { state[2]++; i++; }
            if (i == h) return Float.NaN;
            while (i < h && bin[i * w + startX] == 0 && state[3] <= maxCount) { state[3]++; i++; }
            if (i == h || state[3] > maxCount) return Float.NaN;
            while (i < h && bin[i * w + startX] != 0 && state[4] <= maxCount) { state[4]++; i++; }
            if (state[4] > maxCount) return Float.NaN;

            return checkRatio(state) ? (i - state[4] - state[3] - state[2] / 2.0f) : Float.NaN;
        }

        private float crossCheckHorizontal(int startX, int startY, int maxCount) {
            if (startY < 0 || startY >= h) return Float.NaN;
            int[] state = new int[5];
            int i = startX;
            while (i >= 0 && bin[startY * w + i] != 0) { state[2]++; i--; }
            if (i < 0) return Float.NaN;
            while (i >= 0 && bin[startY * w + i] == 0 && state[1] <= maxCount) { state[1]++; i--; }
            if (i < 0 || state[1] > maxCount) return Float.NaN;
            while (i >= 0 && bin[startY * w + i] != 0 && state[0] <= maxCount) { state[0]++; i--; }
            if (state[0] > maxCount) return Float.NaN;

            i = startX + 1;
            while (i < w && bin[startY * w + i] != 0) { state[2]++; i++; }
            if (i == w) return Float.NaN;
            while (i < w && bin[startY * w + i] == 0 && state[3] <= maxCount) { state[3]++; i++; }
            if (i == w || state[3] > maxCount) return Float.NaN;
            while (i < w && bin[startY * w + i] != 0 && state[4] <= maxCount) { state[4]++; i++; }
            if (state[4] > maxCount) return Float.NaN;

            return checkRatio(state) ? (i - state[4] - state[3] - state[2] / 2.0f) : Float.NaN;
        }

        private void mergeFinder(List<Finder> finders, float x, float y, float modSize) {
            for (Finder f : finders) {
                if (Math.abs(f.x - x) <= modSize && Math.abs(f.y - y) <= modSize) {
                    f.x = (f.x * f.count + x) / (f.count + 1);
                    f.y = (f.y * f.count + y) / (f.count + 1);
                    f.count++;
                    return;
                }
            }
            finders.add(new Finder(x, y, modSize));
        }

        private float distSq(Finder a, Finder b) {
            return (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y);
        }

        private int estimateDimension(Finder[] f) {
            Finder tl = f[0], tr = f[1], bl = f[2];
            float modSize = (tl.modSize + tr.modSize + bl.modSize) / 3.0f;
            float distTop = (float) Math.sqrt(distSq(tl, tr));
            float distLeft = (float) Math.sqrt(distSq(tl, bl));
            
            int estimated = Math.round(((distTop + distLeft) / 2.0f) / modSize) + 7;
            int rem = estimated % 4;
            if (rem != 1) estimated += (rem == 0 ? 1 : (rem == 2 ? -1 : (estimated % 2 == 0 ? 1 : 2)));
            return estimated;
        }

        private Finder locateAlignment(Finder tl, Finder tr, Finder bl, int targetDim, float modSize) {
            if (targetDim < 25) return null;
            
            float cRatio = (targetDim - 10.0f) / (targetDim - 7.0f);
            float dx = (tr.x - tl.x) * cRatio;
            float dy = (tr.y - tl.y) * cRatio;
            float dx2 = (bl.x - tl.x) * cRatio;
            float dy2 = (bl.y - tl.y) * cRatio;
            
            int guessX = (int) (tl.x + dx + dx2 + 0.5f);
            int guessY = (int) (tl.y + dy + dy2 + 0.5f);
            
            int rad = (int) (modSize * 6);
            if (guessX - rad < 0 || guessX + rad >= w || guessY - rad < 0 || guessY + rad >= h) return null;
            
            float bestX = Float.NaN, bestY = Float.NaN;
            float minPenalty = Float.MAX_VALUE;

            for (int y = guessY - rad; y <= guessY + rad; y++) {
                for (int x = guessX - rad; x <= guessX + rad; x++) {
                    if (bin[y * w + x] != 0) {
                        int[] hS = new int[3];
                        int cx = x; while (cx >= 0 && bin[y * w + cx] != 0) { hS[1]++; cx--; }
                        float leftBlack = hS[1];
                        while (cx >= 0 && bin[y * w + cx] == 0) { hS[0]++; cx--; }
                        cx = x + 1; while (cx < w && bin[y * w + cx] != 0) { hS[1]++; cx++; }
                        while (cx < w && bin[y * w + cx] == 0) { hS[2]++; cx++; }
                        
                        if (hS[0] == 0 || hS[1] == 0 || hS[2] == 0) continue;
                        if (Math.abs(hS[0] - hS[1]) > hS[1] || Math.abs(hS[2] - hS[1]) > hS[1]) continue;

                        int[] vS = new int[3];
                        int cy = y; while (cy >= 0 && bin[cy * w + x] != 0) { vS[1]++; cy--; }
                        float topBlack = vS[1];
                        while (cy >= 0 && bin[cy * w + x] == 0) { vS[0]++; cy--; }
                        cy = y + 1; while (cy < h && bin[cy * w + x] != 0) { vS[1]++; cy++; }
                        while (cy < h && bin[cy * w + x] == 0) { vS[2]++; cy++; }

                        if (vS[0] == 0 || vS[1] == 0 || vS[2] == 0) continue;
                        if (Math.abs(vS[0] - vS[1]) > vS[1] || Math.abs(vS[2] - vS[1]) > vS[1]) continue;

                        float penalty = Math.abs(hS[0] - hS[1]) + Math.abs(hS[2] - hS[1]) + Math.abs(vS[0] - vS[1]) + Math.abs(vS[2] - vS[1]);
                        if (penalty < minPenalty) {
                            minPenalty = penalty;
                            bestX = x - leftBlack + hS[1] / 2.0f;
                            bestY = y - topBlack + vS[1] / 2.0f;
                        }
                    }
                }
            }
            if (!Float.isNaN(bestX)) return new Finder(bestX, bestY, modSize);
            return null;
        }

        private byte[] extractGrid(Finder[] f, int targetDim) {
            Finder tl = f[0], tr = f[1], bl = f[2];
            float modSize = (tl.modSize + tr.modSize + bl.modSize) / 3.0f;
            
            Finder align = locateAlignment(tl, tr, bl, targetDim, modSize);
            Perspective transform;

            if (align != null) {
                transform = Perspective.quadToQuad(
                    3.0f, 3.0f, 
                    targetDim - 4.0f, 3.0f, 
                    targetDim - 7.0f, targetDim - 7.0f, 
                    3.0f, targetDim - 4.0f,
                    tl.x, tl.y, tr.x, tr.y, align.x, align.y, bl.x, bl.y
                );
            } else {
                float brX = tr.x - tl.x + bl.x;
                float brY = tr.y - tl.y + bl.y;
                transform = Perspective.quadToQuad(
                    3.0f, 3.0f, 
                    targetDim - 4.0f, 3.0f, 
                    targetDim - 4.0f, targetDim - 4.0f, 
                    3.0f, targetDim - 4.0f,
                    tl.x, tl.y, tr.x, tr.y, brX, brY, bl.x, bl.y
                );
            }
            
            if (transform == null) return null;

            byte[] grid = new byte[targetDim * targetDim];
            float[] pt = new float[2];
            for (int y = 0; y < targetDim; y++) {
                for (int x = 0; x < targetDim; x++) {
                    pt[0] = x; pt[1] = y;
                    transform.transform(pt);
                    int ix = (int) (pt[0] + 0.5f);
                    int iy = (int) (pt[1] + 0.5f);
                    if (ix >= 0 && ix < w && iy >= 0 && iy < h) {
                        grid[y * targetDim + x] = bin[iy * w + ix];
                    }
                }
            }
            return grid;
        }

        int dataBytes, eccBytes, blocks;
        
        private byte[] unmaskAndRead(byte[] grid) {
            int fmt1 = 0, fmt2 = 0;
            for (int i = 0; i < 6; i++) fmt1 = (fmt1 << 1) | grid[8 * dim + i];
            fmt1 = (fmt1 << 1) | grid[8 * dim + 7];
            fmt1 = (fmt1 << 1) | grid[8 * dim + 8];
            fmt1 = (fmt1 << 1) | grid[7 * dim + 8];
            for (int i = 5; i >= 0; i--) fmt1 = (fmt1 << 1) | grid[i * dim + 8];

            for (int i = 0; i < 7; i++) fmt2 = (fmt2 << 1) | grid[(dim - 1 - i) * dim + 8];
            for (int i = 0; i < 8; i++) fmt2 = (fmt2 << 1) | grid[8 * dim + (dim - 8 + i)];
            
            int bestFormat = -1;
            int minDiff = 15;
            for (int i = 0; i < 32; i++) {
                int diff1 = Integer.bitCount(fmt1 ^ FORMAT_INFO_DECODE[i]);
                int diff2 = Integer.bitCount(fmt2 ^ FORMAT_INFO_DECODE[i]);
                int diff = Math.min(diff1, diff2);
                if (diff < minDiff) {
                    minDiff = diff;
                    bestFormat = i;
                }
            }
            if (minDiff > 3) return null; 
            
            int mask = bestFormat & 0x07;
            byte[] rawBytes = new byte[4096]; // High capacity allocation
            int bits = 0, current = 0, byteIdx = 0;
            boolean upward = true;

            for (int x = dim - 1; x > 0; x -= 2) {
                if (x == 6) x--;
                for (int y = 0; y < dim; y++) {
                    int r = upward ? dim - 1 - y : y;
                    for (int cOffset = 0; cOffset < 2; cOffset++) {
                        int c = x - cOffset;
                        if ((r < 9 && c < 9) || (r < 9 && c >= dim - 8) || (r >= dim - 8 && c < 9) || r == 6 || c == 6) continue;
                        
                        if (version >= 7) {
                            if (r < 6 && c >= dim - 11 && c <= dim - 9) continue;
                            if (c < 6 && r >= dim - 11 && r <= dim - 9) continue;
                        }
                        
                        if (isAlignment(c, r)) continue;

                        int bit = grid[r * dim + c];
                        if (applyMask(mask, c, r)) bit ^= 1;

                        current = (current << 1) | bit;
                        if (++bits == 8) {
                            if (byteIdx < rawBytes.length) rawBytes[byteIdx++] = (byte) current;
                            bits = current = 0;
                        }
                    }
                }
                upward = !upward;
            }

            int eccLevel = (bestFormat >> 3) & 0x03;
            int eccIdx = (eccLevel == 1) ? 0 : (eccLevel == 0) ? 1 : (eccLevel == 3) ? 2 : 3;
            
            this.eccBytes = TOT_ECC[version - 1][eccIdx];
            this.blocks = NUM_BLK[version - 1][eccIdx];
            this.dataBytes = byteIdx - this.eccBytes;
            if (this.dataBytes <= 0) return null;

            byte[] trimmed = new byte[byteIdx];
            System.arraycopy(rawBytes, 0, trimmed, 0, byteIdx);
            return trimmed;
        }

        private byte[] errorCorrect(byte[] rawData) {
            int eccPerBlock = eccBytes / blocks;
            int shortBlock = dataBytes / blocks;
            int numLong = dataBytes % blocks;
            int numShort = blocks - numLong;

            byte[] decoded = new byte[dataBytes];
            int dataIdx = 0;

            for (int b = 0; b < blocks; b++) {
                int dLen = shortBlock + (b >= numShort ? 1 : 0);
                int[] rsBlock = new int[dLen + eccPerBlock];

                for (int i = 0; i < dLen; i++) {
                    int idx = (i < shortBlock) ? (i * blocks + b) : 
                              (shortBlock * blocks + (i - shortBlock) * numLong + (b - numShort));
                    rsBlock[i] = rawData[idx] & 0xFF;
                }
                for (int i = 0; i < eccPerBlock; i++) {
                    rsBlock[dLen + i] = rawData[dataBytes + i * blocks + b] & 0xFF;
                }

                if (!correctBlock(rsBlock, dLen, eccPerBlock)) return null;

                for (int i = 0; i < dLen; i++) decoded[dataIdx++] = (byte) rsBlock[i];
            }
            return decoded;
        }

        private boolean correctBlock(int[] cw, int dLen, int eLen) {
            int N = dLen + eLen;
            int[] S = new int[256];
            boolean hasErr = false;

            for (int j = 0; j < eLen; j++) {
                int syn = 0;
                for (int i = 0; i < N; i++) syn = gfMul(syn, expTable[j]) ^ cw[i];
                S[j] = syn;
                if (syn != 0) hasErr = true;
            }
            if (!hasErr) return true;

            int[] C = new int[256], B = new int[256];
            C[0] = B[0] = 1;
            int L = 0, m = 1, b = 1;

            for (int n = 0; n < eLen; n++) {
                int d = S[n];
                for (int i = 1; i <= L; i++) d ^= gfMul(C[i], S[n - i]);
                if (d == 0) { m++; } 
                else if (2 * L <= n) {
                    int[] T = java.util.Arrays.copyOf(C, 256);
                    int coef = gfMul(d, gfInv(b));
                    for (int i = m; i < 256; i++) C[i] ^= gfMul(coef, B[i - m]);
                    L = n + 1 - L;
                    System.arraycopy(T, 0, B, 0, 256);
                    b = d; m = 1;
                } else {
                    int coef = gfMul(d, gfInv(b));
                    for (int i = m; i < 256; i++) C[i] ^= gfMul(coef, B[i - m]);
                    m++;
                }
            }
            
            if (L > eLen / 2) return false;

            int[] errPos = new int[128];
            int errCnt = 0;
            for (int i = 0; i < N; i++) {
                int p = N - 1 - i, eval = 0, xPow = 1, xInv = expTable[(255 - (p % 255)) % 255];
                for (int j = 0; j <= L; j++) { eval ^= gfMul(C[j], xPow); xPow = gfMul(xInv, xPow); }
                if (eval == 0 && errCnt < 128) errPos[errCnt++] = i;
            }
            if (errCnt != L) return false;

            int[] omega = new int[256];
            for (int i = 0; i < eLen; i++) {
                for (int j = 0; j <= L && i + j < eLen; j++) omega[i + j] ^= gfMul(S[i], C[j]);
            }

            for (int e = 0; e < errCnt; e++) {
                int idx = errPos[e], p = N - 1 - idx, num = 0, den = 0, xPow = 1, xInv = expTable[(255 - (p % 255)) % 255];
                for (int j = 0; j < eLen; j++) { num ^= gfMul(omega[j], xPow); xPow = gfMul(xPow, xInv); }
                xPow = 1;
                for (int j = 1; j <= L; j += 2) { den ^= gfMul(C[j], xPow); xPow = gfMul(xPow, gfMul(xInv, xInv)); }
                if (den == 0) return false;
                cw[idx] ^= gfMul(gfMul(num, gfInv(den)), expTable[p % 255]); 
            }
            return true;
        }

        private String parseMessage(byte[] data) {
            StringBuilder sb = new StringBuilder();
            BitStream stream = new BitStream(data);
            
            while (stream.available() >= 4) {
                int mode = stream.read(4);
                if (mode == 0) break;
                
                if (mode == 1) { 
                    int lenBits = version < 10 ? 10 : 12;
                    if (stream.available() < lenBits) break;
                    int len = stream.read(lenBits);
                    for (int i = 0; i < len; i += 3) {
                        int rem = len - i;
                        int readLen = rem >= 3 ? 10 : (rem == 2 ? 7 : 4);
                        if (stream.available() < readLen) break;
                        int val = stream.read(readLen);
                        if (rem >= 3) {
                            sb.append((char)('0' + val / 100)).append((char)('0' + (val / 10) % 10)).append((char)('0' + val % 10));
                        } else if (rem == 2) {
                            sb.append((char)('0' + val / 10)).append((char)('0' + val % 10));
                        } else {
                            sb.append((char)('0' + val));
                        }
                    }
                } else if (mode == 2) { 
                    int lenBits = version < 10 ? 9 : 11;
                    if (stream.available() < lenBits) break;
                    int len = stream.read(lenBits);
                    String alpha = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
                    for (int i = 0; i < len; i += 2) {
                        int rem = len - i;
                        int readLen = rem >= 2 ? 11 : 6;
                        if (stream.available() < readLen) break;
                        int val = stream.read(readLen);
                        if (rem >= 2) {
                            int c1 = val / 45, c2 = val % 45;
                            sb.append(c1 < alpha.length() ? alpha.charAt(c1) : '?');
                            sb.append(c2 < alpha.length() ? alpha.charAt(c2) : '?');
                        } else {
                            sb.append(val < alpha.length() ? alpha.charAt(val) : '?');
                        }
                    }
                } else if (mode == 4) { 
                    int lenBits = version < 10 ? 8 : 16;
                    if (stream.available() < lenBits) break;
                    int len = stream.read(lenBits);
                    if (stream.available() < len * 8) break;
                    for (int i = 0; i < len; i++) sb.append((char) stream.read(8));
                } else if (mode == 7) { 
                    if (stream.available() < 8) break;
                    int val = stream.read(8);
                    if ((val & 0x80) != 0) {
                        int extra = (val & 0xC0) == 0x80 ? 8 : 16;
                        if (stream.available() < extra) break;
                        stream.read(extra);
                    }
                } else { break; }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        private boolean applyMask(int mask, int c, int r) {
            switch (mask) {
                case 0: return (r + c) % 2 == 0;
                case 1: return r % 2 == 0;
                case 2: return c % 3 == 0;
                case 3: return (r + c) % 3 == 0;
                case 4: return (r / 2 + c / 3) % 2 == 0;
                case 5: return (r * c) % 2 + (r * c) % 3 == 0;
                case 6: return ((r * c) % 2 + (r * c) % 3) % 2 == 0;
                case 7: return ((r + c) % 2 + (r * c) % 3) % 2 == 0;
            }
            return false;
        }

        // Dynamically compute Alignment Pattern coordinates to support any QR standard dimension.
        private boolean isAlignment(int c, int r) {
            if (version < 2) return false;
            int intervals = version / 7 + 1;
            int step = Math.round((dim - 13.0f) / intervals);
            if (step % 2 == 1) step++;
            
            int[] coords = new int[intervals + 1];
            coords[0] = 6;
            for (int i = 1; i <= intervals; i++) coords[i] = (dim - 7) - step * (intervals - i);
            
            for (int cx : coords) {
                for (int cy : coords) {
                    if ((cx == 6 && cy == 6) || (cx == 6 && cy >= dim - 9) || (cx >= dim - 9 && cy == 6)) continue;
                    if (c >= cx - 2 && c <= cx + 2 && r >= cy - 2 && r <= cy + 2) return true;
                }
            }
            return false;
        }
    }

    // --- Math & Helper Classes ---
    
    private static class Perspective {
        float a11, a12, a13, a21, a22, a23, a31, a32, a33;
        
        static Perspective squareToQuad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            float dx3 = x0 - x1 + x2 - x3, dy3 = y0 - y1 + y2 - y3;
            if (Math.abs(dx3) < 1e-4f && Math.abs(dy3) < 1e-4f) {
                Perspective p = new Perspective();
                p.a11 = x1 - x0; p.a12 = x2 - x1; p.a13 = x0;
                p.a21 = y1 - y0; p.a22 = y2 - y1; p.a23 = y0;
                p.a31 = 0; p.a32 = 0; p.a33 = 1.0f;
                return p;
            } else {
                float dx1 = x1 - x2, dy1 = y1 - y2;
                float dx2 = x3 - x2, dy2 = y3 - y2;
                float det = dx1 * dy2 - dx2 * dy1;
                if (det == 0.0f) return null;
                float a31 = (dx3 * dy2 - dx2 * dy3) / det;
                float a32 = (dx1 * dy3 - dx3 * dy1) / det;
                Perspective p = new Perspective();
                p.a11 = x1 - x0 + a31 * x1;
                p.a12 = x3 - x0 + a32 * x3;
                p.a13 = x0;
                p.a21 = y1 - y0 + a31 * y1;
                p.a22 = y3 - y0 + a32 * y3;
                p.a23 = y0;
                p.a31 = a31;
                p.a32 = a32;
                p.a33 = 1.0f;
                return p;
            }
        }
        
        static Perspective quadToSquare(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            Perspective q = squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3);
            if (q == null) return null;
            Perspective p = new Perspective();
            p.a11 = q.a22 * q.a33 - q.a23 * q.a32;
            p.a21 = q.a23 * q.a31 - q.a21 * q.a33;
            p.a31 = q.a21 * q.a32 - q.a22 * q.a31;
            p.a12 = q.a13 * q.a32 - q.a12 * q.a33;
            p.a22 = q.a11 * q.a33 - q.a13 * q.a31;
            p.a32 = q.a12 * q.a31 - q.a11 * q.a32;
            p.a13 = q.a12 * q.a23 - q.a13 * q.a22;
            p.a23 = q.a13 * q.a21 - q.a11 * q.a23;
            p.a33 = q.a11 * q.a22 - q.a12 * q.a21;
            return p;
        }

        Perspective times(Perspective other) {
            Perspective p = new Perspective();
            p.a11 = a11 * other.a11 + a12 * other.a21 + a13 * other.a31;
            p.a12 = a11 * other.a12 + a12 * other.a22 + a13 * other.a32;
            p.a13 = a11 * other.a13 + a12 * other.a23 + a13 * other.a33;
            p.a21 = a21 * other.a11 + a22 * other.a21 + a23 * other.a31;
            p.a22 = a21 * other.a12 + a22 * other.a22 + a23 * other.a32;
            p.a23 = a21 * other.a13 + a22 * other.a23 + a23 * other.a33;
            p.a31 = a31 * other.a11 + a32 * other.a21 + a33 * other.a31;
            p.a32 = a31 * other.a12 + a32 * other.a22 + a33 * other.a32;
            p.a33 = a31 * other.a13 + a32 * other.a23 + a33 * other.a33;
            return p;
        }
        
        static Perspective quadToQuad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                                      float x0p, float y0p, float x1p, float y1p, float x2p, float y2p, float x3p, float y3p) {
            Perspective qToS = quadToSquare(x0, y0, x1, y1, x2, y2, x3, y3);
            Perspective sToQ = squareToQuad(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p);
            if (qToS == null || sToQ == null) return null;
            return sToQ.times(qToS);
        }

        void transform(float[] point) {
            float x = point[0], y = point[1];
            float denominator = a31 * x + a32 * y + a33;
            point[0] = (a11 * x + a12 * y + a13) / denominator;
            point[1] = (a21 * x + a22 * y + a23) / denominator;
        }
    }

    private static class Finder {
        float x, y, modSize; int count = 1;
        Finder(float x, float y, float m) { this.x = x; this.y = y; this.modSize = m; }
    }

    private static class BitStream {
        private final byte[] data;
        private int offset = 0;
        BitStream(byte[] data) { this.data = data; }
        
        int available() { return data.length * 8 - offset; }
        
        int read(int numBits) {
            if (numBits == 0 || available() < numBits) return 0;
            int res = 0;
            for (int i = 0; i < numBits; i++, offset++) {
                res = (res << 1) | ((data[offset / 8] >> (7 - (offset % 8))) & 1);
            }
            return res;
        }
    }
}
