package com.barcodetyper;

public class UnifiedScanner {
    public static String scan(byte[] yPlane, int w, int h, int rowStride) {
        String oneD = OneDScanner.scanBuffer(yPlane, w, h, rowStride);
        if (oneD != null) return oneD;

        String pdf = PDF417Scanner.scanBuffer(yPlane, w, h, rowStride);
        if (pdf != null) return pdf;

        return DataMatrixScanner.scanBuffer(yPlane, w, h, rowStride);
    }
}
