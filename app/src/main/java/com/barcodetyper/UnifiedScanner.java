/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class UnifiedScanner {
    public static String scan(byte[] yPlane, int w, int h, int rowStride) {
        
        String qr = QRScanner.scanBuffer(yPlane, w, h, rowStride);
        if (qr != null) return qr;

        String oneD = OneDScanner.scanBuffer(yPlane, w, h, rowStride);
        if (oneD != null) return oneD;

        String pdf = PDF417Scanner.scanBuffer(yPlane, w, h, rowStride);
        if (pdf != null) return pdf;

        return DataMatrixScanner.scanBuffer(yPlane, w, h, rowStride);
    }
}