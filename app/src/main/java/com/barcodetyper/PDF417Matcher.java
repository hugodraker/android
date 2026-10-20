package package com.barcodetyper;

public class PDF417Matcher {
    public static int matchCodewordRuns(float[] r8, float expectedW, int cluster, float[] outErr) {
        float s = 0.0f;
        for (int k = 0; k < 8; k++) s += r8[k];
        if (s < 4.5f) return -1;
        if (expectedW > 0.0f && (s < expectedW * 0.78f || s > expectedW * 1.22f)) return -1;

        float scale = 17.0f / s;
        float[] n = new float[8], t = new float[7];
        for (int k = 0; k < 8; k++) {
            n[k] = r8[k] * scale;
            if (n[k] < 0.35f || n[k] > 6.85f) return -1;
        }
        for (int k = 0; k < 7; k++) t[k] = n[k] + n[k + 1];

        int bestCw = -1;
        float bestErr = 999.0f, secondErr = 999.0f;
        byte[][] cRuns = PDF417Data.pdfRuns[cluster];
        byte[][] cT = PDF417Data.pdfT[cluster];

        for (int w = 0; w < 929; w++) {
            float tErr = 0.0f;
            boolean ok = true;
            for (int k = 0; k < 7; k++) {
                float dt = Math.abs(t[k] - (float) cT[w][k]);
                if (dt > 0.80f) { ok = false; break; }
                tErr += dt;
            }
            if (!ok || tErr >= secondErr) continue;

            float rErr = 0.0f;
            for (int k = 0; k < 8; k++) {
                float dr = Math.abs(n[k] - (float) cRuns[w][k]);
                if (dr > 0.80f) { ok = false; break; }
                rErr += dr;
            }
            if (!ok) continue;

            float err = tErr + 0.50f * rErr;
            if (err < bestErr) {
                secondErr = bestErr;
                bestErr = err;
                bestCw = w;
            } else if (err < secondErr) {
                secondErr = err;
            }
        }

        if (bestCw != -1 && bestErr < 2.20f && (secondErr - bestErr) >= 0.18f) {
            if (outErr != null && outErr.length > 0) outErr[0] = bestErr;
            return bestCw;
        }
        return -1;
    }

    public static boolean checkStartPattern(float[] r8, float[] outMod) {
        final float[] pat = {8, 1, 1, 1, 1, 1, 1, 3};
        float s = 0.0f;
        for (int k = 0; k < 8; k++) s += r8[k];
        float mod = s / 17.0f;
        if (mod < 0.45f) return false;

        float n0 = r8[0] / mod, n7 = r8[7] / mod;
        if (n0 < 6.2f || n0 > 9.8f || n7 < 2.0f || n7 > 4.0f) return false;

        float err = Math.abs(n0 - 8.0f) + Math.abs(n7 - 3.0f);
        for (int k = 1; k <= 6; k++) {
            float nk = r8[k] / mod;
            if (nk < 0.35f || nk > 1.85f) return false;
            err += Math.abs(nk - pat[k]);
        }
        if (err > 2.6f) return false;
        if (outMod != null && outMod.length > 0) outMod[0] = mod;
        return true;
    }

    public static boolean checkStopPattern(float[] r9, int availRuns, float[] outMod) {
        final float[] pat = {7, 1, 1, 3, 1, 1, 1, 2, 1};
        if (availRuns < 9) return false;
        float s = 0.0f;
        for (int k = 0; k < 9; k++) s += r9[k];
        float mod = s / 18.0f;
        if (mod < 0.45f) return false;

        float n0 = r9[0] / mod, n3 = r9[3] / mod, n7 = r9[7] / mod, n8 = r9[8] / mod;
        if (n0 < 5.4f || n0 > 8.6f || n3 < 2.0f || n3 > 4.0f ||
            n7 < 1.2f || n7 > 2.8f || n8 < 0.35f || n8 > 1.85f) return false;

        float err = Math.abs(n0 - 7.0f) + Math.abs(n3 - 3.0f) + Math.abs(n7 - 2.0f) + Math.abs(n8 - 1.0f);
        int[] onesIdx = {1, 2, 4, 5, 6};
        for (int i = 0; i < 5; i++) {
            float nk = r9[onesIdx[i]] / mod;
            if (nk < 0.35f || nk > 1.85f) return false;
            err += Math.abs(nk - 1.0f);
        }
        if (err > 2.8f) return false;
        if (outMod != null && outMod.length > 0) outMod[0] = mod;
        return true;
    }
}