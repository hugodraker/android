/* ============================================================================
 * Android Barcode Typer
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

public class UnifiedScanner {
    public static String scan(byte[] yPlane, int w, int h, int rowStride) {
        
        // 1. Sweep for QR Codes
        String qr = QRScanner.scanBuffer(yPlane, w, h, rowStride);
        if (qr != null) return qr;

        // 2. Sweep for 1D Barcodes
        String oneD = OneDScanner.scanBuffer(yPlane, w, h, rowStride);
        if (oneD != null) return oneD;

        // 3. Sweep for PDF417 Codes
        String pdf = PDF417Scanner.scanBuffer(yPlane, w, h, rowStride);
        if (pdf != null) return pdf;

        // 4. Sweep for DataMatrix (ECC200)
        return DataMatrixScanner.scanBuffer(yPlane, w, h, rowStride);
    }
}
