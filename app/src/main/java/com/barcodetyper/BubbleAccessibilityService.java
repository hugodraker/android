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
 * @echo off
 * set "SDK=%LOCALAPPDATA%\Android\Sdk"
 * set "BUILD_TOOLS=%SDK%\build-tools\34.0.0"
 * set "PLATFORM=%SDK%\platforms\android-34\android.jar"
 * 
 * mkdir build\res build\gen build\apk build\obj build\dex 2>nul
 * 
 * "%BUILD_TOOLS%\aapt2.exe" compile --dir app\src\main\res -o build\res\compiled_res.zip
 * 
 * "%BUILD_TOOLS%\aapt2.exe" link build\res\compiled_res.zip ^
 *     -I "%PLATFORM%" ^
 *     --manifest app\src\main\AndroidManifest.xml ^
 *     --java build\gen ^
 *    -o build\apk\app-unaligned.apk
 * 
 * javac -d build\obj ^
 *     -cp "%PLATFORM%" ^
 *     -source 17 -target 17 ^
 *     build\gen\com\barcodetyper\R.java ^
 *     app\src\main\java\com\barcodetyper\*.java
 *
 * call "%BUILD_TOOLS%\d8.bat" ^
 *     --output build\dex ^
 *     --lib "%PLATFORM%" ^
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
 * call "%BUILD_TOOLS%\apksigner.bat" sign --ks my-release-key.keystore --ks-pass pass:android --out build\apk\barcodetyper.apk build\apk\app-aligned.apk
 * 
 * THIS WORK IS NOT FIT FOR ANY FUNCTION OR PURPOSE, COMES WITH NO WARRANTY,
 * AND IS BEING RELEASED INTO THE PUBLIC DOMAIN.
 * ============================================================================ */
package com.barcodetyper;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BubbleAccessibilityService extends AccessibilityService implements LifecycleOwner {
    private WindowManager windowManager;
    private FrameLayout bubbleView;
    private FrameLayout scannerOverlay;
    private WindowManager.LayoutParams bubbleParams;
    
    private LifecycleRegistry lifecycleRegistry;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private PreviewView viewFinder;

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        bubbleView = new FrameLayout(this);
        Button btn = new Button(this);
        
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xBB000000); 
        shape.setStroke(4, 0xFFFFFFFF); 
        btn.setBackground(shape);

        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(160, 160);
        bubbleView.addView(btn, btnParams);

        bubbleParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 0;
        bubbleParams.y = 300;

        btn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false;
                        bubbleParams.x = initialX + (int) dx;
                        bubbleParams.y = initialY + (int) dy;
                        windowManager.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) openScannerOverlay();
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubbleView, bubbleParams);
    }

    private void openScannerOverlay() {
        if (scannerOverlay == null) {
            scannerOverlay = new FrameLayout(this);
            
            GradientDrawable overlayBg = new GradientDrawable();
            overlayBg.setColor(0xFF222222);
            overlayBg.setCornerRadius(32f);
            overlayBg.setStroke(6, 0xFFFFFFFF);
            scannerOverlay.setBackground(overlayBg);
            scannerOverlay.setPadding(10, 10, 10, 10);

            viewFinder = new PreviewView(this);
            FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            previewParams.bottomMargin = 140; 
            scannerOverlay.addView(viewFinder, previewParams);

            Button btnClose = new Button(this);
            btnClose.setText("Close");
            btnClose.setOnClickListener(v -> closeScannerOverlay());
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            closeParams.gravity = Gravity.BOTTOM | Gravity.START;
            closeParams.bottomMargin = 20;
            closeParams.leftMargin = 30;
            scannerOverlay.addView(btnClose, closeParams);
            
            Button btnSwitch = new Button(this);
            btnSwitch.setText("Flip");
            btnSwitch.setOnClickListener(v -> {
                lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) 
                    ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
                bindCameraUseCases();
            });
            FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            switchParams.gravity = Gravity.BOTTOM | Gravity.END;
            switchParams.bottomMargin = 20;
            switchParams.rightMargin = 30;
            scannerOverlay.addView(btnSwitch, switchParams);
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            600, 700, 
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        windowManager.addView(scannerOverlay, params);
        bubbleView.setVisibility(View.GONE);
        
        startCamera();
    }

    private void startCamera() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) { e.printStackTrace(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            byte[] yPlane = new byte[yBuffer.remaining()];
            yBuffer.get(yPlane);

            String result = UnifiedScanner.scan(yPlane, image.getWidth(), image.getHeight(), image.getPlanes()[0].getRowStride());
            if (result != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    closeScannerOverlay();
                    typeText(result);
                });
            }
            image.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    private void closeScannerOverlay() {
        if (scannerOverlay != null && scannerOverlay.getParent() != null) {
            windowManager.removeView(scannerOverlay);
        }
        bubbleView.setVisibility(View.VISIBLE);
        if (cameraProvider != null) cameraProvider.unbindAll();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
    }

    private void typeText(String newText) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                CharSequence currentText = focused.getText();
                if (currentText == null) currentText = "";
                
                int start = focused.getTextSelectionStart();
                int end = focused.getTextSelectionEnd();
                
                if (start < 0) start = currentText.length();
                if (end < 0) end = currentText.length();
                
                int min = Math.min(start, end);
                int max = Math.max(start, end);
                
                String updatedText = currentText.subSequence(0, min).toString() + newText + currentText.subSequence(max, currentText.length()).toString();
                
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updatedText);
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                
                Bundle selectionArgs = new Bundle();
                selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, min + newText.length());
                selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, min + newText.length());
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs);
            }
        }
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (bubbleView != null && bubbleView.getParent() != null) windowManager.removeView(bubbleView);
        if (scannerOverlay != null && scannerOverlay.getParent() != null) windowManager.removeView(scannerOverlay);
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
