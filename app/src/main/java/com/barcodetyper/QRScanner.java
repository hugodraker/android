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

    // Galois Field tables for Reed-Solomon (QR uses polynomial 0x11D)
    private static final int[] expTable = new int[512];
    private static final int[] logTable = new int[256];
    
    // 32 Valid BCH(15,5) Format Information Strings (pre-XOR'd with 0x5412)
    private static final int[] FORMAT_INFO_DECODE = {
        0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0,
        0x77C4, 0x72F3, 0x7DAA, 0x789D, 0x662F, 0x6318, 0x6C41, 0x6976,
        0x1689, 0x13BE, 0x1CE7, 0x19D0, 0x0762, 0x0255, 0x0D0C, 0x083B,
        0x355F, 0x3068, 0x3F31, 0x3A06, 0x24B4, 0x2183, 0x2EDA, 0x2BED
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
            // Fail-safe wrapper ensures the app never crashes from malformed/noisy buffers
            return null;
        }
    }

    // --- Object-Oriented QR Decoder ---
    private static class Decoder {
        private final byte[] yPlane;
        private final int w, h, stride;
        private byte[] bin;

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

            byte[] grid = extractGrid(finders);
            if (grid == null) return null;

            byte[] rawData = unmaskAndRead(grid);
            if (rawData == null) return null;

            byte[] corrected = errorCorrect(rawData);
            if (corrected == null) return null;

            return parseMessage(corrected);
        }

        private void binarize() {
            int rad = Math.max(12, Math.min(w, h) / 8);
            
            // Fix: High resolution mobile camera frames overflow a 32-bit integer integral image
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
                                if (checkRatio(state)) {
                                    float cx = x - state[4] - state[3] - state[2] / 2.0f;
                                    float cy = crossCheckVertical((int) (cx + 0.5f), y, state[2] * 2);
                                    if (!Float.isNaN(cy)) {
                                        float finalX = crossCheckHorizontal((int) (cx + 0.5f), (int) (cy + 0.5f), state[2] * 2);
                                        if (!Float.isNaN(finalX)) {
                                            float modSize = (state[0] + state[1] + state[2] + state[3] + state[4]) / 7.0f;
                                            mergeFinder(finders, finalX, cy, modSize);
                                        }
                                    }
                                    state[0] = state[2]; state[1] = state[3]; state[2] = state[4];
                                    state[3] = 1; state[4] = 0; currentState = 3;
                                } else {
                                    state[0] = state[2]; state[1] = state[3]; state[2] = state[4];
                                    state[3] = 1; state[4] = 0; currentState = 3;
                                }
                            } else {
                                currentState++; state[currentState]++;
                            }
                        } else {
                            state[currentState]++;
                        }
                    }
                }
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
            int maxVar = Math.max(mod / 2, 200);
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

        int dim, version;
        private byte[] extractGrid(Finder[] f) {
            Finder tl = f[0], tr = f[1], bl = f[2];
            float modSize = (tl.modSize + tr.modSize + bl.modSize) / 3.0f;
            float distTop = (float) Math.sqrt(distSq(tl, tr));
            float distLeft = (float) Math.sqrt(distSq(tl, bl));
            
            dim = Math.round(((distTop + distLeft) / 2.0f) / modSize) + 7;
            int rem = dim % 4;
            if (rem != 1) dim += (rem == 0 ? 1 : (rem == 2 ? -1 : (dim % 2 == 0 ? 1 : 2)));
            if (dim < 21 || dim > 57) return null;
            version = (dim - 17) / 4;

            float pTL = 3.0f, pTR = dim - 4.0f;
            float dxTR = (tr.x - tl.x) / (pTR - pTL), dyTR = (tr.y - tl.y) / (pTR - pTL);
            float dxBL = (bl.x - tl.x) / (pTR - pTL), dyBL = (bl.y - tl.y) / (pTR - pTL);

            byte[] grid = new byte[dim * dim];
            for (int y = 0; y < dim; y++) {
                float rowX = tl.x + dxBL * (y - pTL);
                float rowY = tl.y + dyBL * (y - pTL);
                for (int x = 0; x < dim; x++) {
                    int ix = (int) (rowX + dxTR * (x - pTL) + 0.5f);
                    int iy = (int) (rowY + dyTR * (x - pTL) + 0.5f);
                    if (ix >= 0 && ix < w && iy >= 0 && iy < h) grid[y * dim + x] = bin[iy * w + ix];
                }
            }
            return grid;
        }

        private byte[] unmaskAndRead(byte[] grid) {
            // Fix: Add parsing fallback using redundant Format Information location.
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
            byte[] rawBytes = new byte[1024]; // Safety net bump
            int bits = 0, current = 0, byteIdx = 0;
            boolean upward = true;

            for (int x = dim - 1; x > 0; x -= 2) {
                if (x == 6) x--;
                for (int y = 0; y < dim; y++) {
                    int r = upward ? dim - 1 - y : y;
                    for (int cOffset = 0; cOffset < 2; cOffset++) {
                        int c = x - cOffset;
                        // Skip Finder, Format Info, and Timing Patterns
                        if ((r < 9 && c < 9) || (r < 9 && c >= dim - 8) || (r >= dim - 8 && c < 9) || r == 6 || c == 6) continue;
                        
                        // Fix: Version Information Block overlapping must be explicitly skipped for version >= 7
                        if (version >= 7) {
                            if (r < 6 && c >= dim - 11 && c <= dim - 9) continue;
                            if (c < 6 && r >= dim - 11 && r <= dim - 9) continue;
                        }
                        
                        // Skip Alignment Patterns
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
            
            int[][] totEcc = {
                {7,10,13,17}, {10,16,22,28}, {15,26,36,44}, {20,36,52,64}, {26,48,72,88},
                {36,64,96,112}, {40,72,108,130}, {48,88,132,156}, {60,110,160,192}, {72,130,192,224}
            };
            int[][] numBlk = {
                {1,1,1,1}, {1,1,1,1}, {1,1,2,2}, {1,2,2,4}, {1,2,4,4},
                {2,4,4,4}, {2,4,6,5}, {2,4,6,6}, {2,5,8,8}, {4,5,8,8}
            };

            this.eccBytes = totEcc[version - 1][eccIdx];
            this.blocks = numBlk[version - 1][eccIdx];
            this.dataBytes = byteIdx - this.eccBytes;
            if (this.dataBytes <= 0) return null;

            byte[] trimmed = new byte[byteIdx];
            System.arraycopy(rawBytes, 0, trimmed, 0, byteIdx);
            return trimmed;
        }

        int dataBytes, eccBytes, blocks;
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
            int[] S = new int[128];
            boolean hasErr = false;

            for (int j = 0; j < eLen; j++) {
                int syn = 0;
                for (int i = 0; i < N; i++) syn = gfMul(syn, expTable[j]) ^ cw[i];
                S[j] = syn;
                if (syn != 0) hasErr = true;
            }
            if (!hasErr) return true;

            int[] C = new int[128], B = new int[128];
            C[0] = B[0] = 1;
            int L = 0, m = 1, b = 1;

            for (int n = 0; n < eLen; n++) {
                int d = S[n];
                for (int i = 1; i <= L; i++) d ^= gfMul(C[i], S[n - i]);
                if (d == 0) { m++; } 
                else if (2 * L <= n) {
                    int[] T = java.util.Arrays.copyOf(C, 128);
                    int coef = gfMul(d, gfInv(b));
                    for (int i = m; i < 128; i++) C[i] ^= gfMul(coef, B[i - m]);
                    L = n + 1 - L;
                    System.arraycopy(T, 0, B, 0, 128);
                    b = d; m = 1;
                } else {
                    int coef = gfMul(d, gfInv(b));
                    for (int i = m; i < 128; i++) C[i] ^= gfMul(coef, B[i - m]);
                    m++;
                }
            }
            
            // Fix: Hardcapped RS bounds (`L > 16`) improperly rejected viable recovery blocks
            if (L > eLen / 2) return false;

            int[] errPos = new int[64]; // Fix: Increased maximum position pool
            int errCnt = 0;
            for (int i = 0; i < N; i++) {
                int p = N - 1 - i, eval = 0, xPow = 1, xInv = expTable[(255 - (p % 255)) % 255];
                for (int j = 0; j <= L; j++) { eval ^= gfMul(C[j], xPow); xPow = gfMul(xInv, xPow); }
                if (eval == 0 && errCnt < 64) errPos[errCnt++] = i;
            }
            if (errCnt != L) return false;

            int[] omega = new int[128];
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

        private boolean isAlignment(int c, int r) {
            if (version < 2) return false;
            int[][] alignCoords = {
                {}, {6,18}, {6,22}, {6,26}, {6,30}, {6,34}, {6,22,38}, {6,24,42}, {6,26,46}, {6,28,50}
            };
            if (version > alignCoords.length) return false;
            for (int cx : alignCoords[version - 1]) {
                for (int cy : alignCoords[version - 1]) {
                    if ((cx == 6 && cy == 6) || (cx == 6 && cy >= dim - 9) || (cx >= dim - 9 && cy == 6)) continue;
                    if (c >= cx - 2 && c <= cx + 2 && r >= cy - 2 && r <= cy + 2) return true;
                }
            }
            return false;
        }
    }

    // --- Helper Classes ---
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
