/* ============================================================================
 * Clipboard Barcode Scanner 
 *
 * Compile:
 *   Mac / Linux:
 *   ./gradlew assembleDebug
 *   
 *   Windows:
 *   gradlew.bat assembleDebug
 *
 * @echo off
 * set "SDK=%LOCALAPPDATA%\Android\Sdk"
 * set "BUILD_TOOLS=%SDK%\build-tools\34.0.0"
 * set "PLATFORM=%SDK%\platforms\android-34\android.jar"
 * 
 * mkdir build\res build\gen build\apk build\obj build\dex 2>nul
 * 
 * "%BUILD_TOOLS%\aapt2.exe" compile -dir app\src\main\res -o build\res\compiled_res.zip
 * 
 * "%BUILD_TOOLS%\aapt2.exe" link build\res\compiled_res.zip ^
 *     -I "%PLATFORM%" ^
 *     -manifest app\src\main\AndroidManifest.xml ^
 *     -java build\gen ^
 *    -o build\apk\app-unaligned.apk
 * 
 * javac -d build\obj ^
 *     -cp "%PLATFORM%" ^
 *     -source 17 -target 17 ^
 *     build\gen\com\barcodetyper\R.java ^
 *     app\src\main\java\com\barcodetyper\*.java
 *
 * call "%BUILD_TOOLS%\d8.bat" ^
 *     -output build\dex ^
 *     -lib "%PLATFORM%" ^
  *    build\obj\com\barcodetyper\*.class
 * 
 * cd build\dex
 * jar uf ..\apk\app-unaligned.apk classes.dex
 * cd ..\..
 * 
 * "%BUILD_TOOLS%\zipalign.exe" -p -f 4 build\apk\app-unaligned.apk build\apk\app-aligned.apk
 * 
 * if not exist my-release-key.keystore (
 *     keytool -genkeypair -v -keystore my-release-key.keystore -alias my-key-alias -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android, O=Android, C=US"
 * )
 * 
 * call "%BUILD_TOOLS%\apksigner.bat" sign -ks my-release-key.keystore -ks-pass pass:android -out build\apk\barcodetyper.apk build\apk\app-aligned.apk
 * 
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScannerActivity extends AppCompatActivity {
    private ExecutorService cameraExecutor;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        findViewById(R.id.btnSwitch).setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) 
                ? CameraSelector.LENS_FACING_FRONT 
                : CameraSelector.LENS_FACING_BACK;
            bindCameraUseCases();
        });

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        PreviewView viewFinder = findViewById(R.id.viewFinder);
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            byte[] yPlane = new byte[yBuffer.remaining()];
            yBuffer.get(yPlane);
            
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = image.getPlanes()[0].getRowStride();

            String result = UnifiedScanner.scan(yPlane, width, height, rowStride);
            if (result != null) {
                returnResultToTextField(result);
            }
            image.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void returnResultToTextField(String scannedText) {
        runOnUiThread(() -> {
            Intent intent = new Intent("com.barcodetyper.SCAN_RESULT");
            intent.putExtra("barcode", scannedText);
            sendBroadcast(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
