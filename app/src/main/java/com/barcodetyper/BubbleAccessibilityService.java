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

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
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
    private Camera camera;
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
        shape.setColor(0xAA808080); // Transparent Gray
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
                        if (isClick) checkPermissionsAndOpenScanner();
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubbleView, bubbleParams);
    }

    private void checkPermissionsAndOpenScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            openScannerOverlay();
        }
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
            viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

            FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            previewParams.bottomMargin = 160; 
            scannerOverlay.addView(viewFinder, previewParams);

            LinearLayout buttonLayout = new LinearLayout(this);
            buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
            buttonLayout.setGravity(Gravity.CENTER);

            Button btnClose = new Button(this);
            btnClose.setText("Close");
            btnClose.setOnClickListener(v -> closeScannerOverlay());
            
            Button btnFlash = new Button(this);
            btnFlash.setText("Torch");
            btnFlash.setOnClickListener(v -> {
                if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                    boolean isTorchOn = camera.getCameraInfo().getTorchState().getValue() == androidx.camera.core.TorchState.ON;
                    camera.getCameraControl().enableTorch(!isTorchOn);
                }
            });
            
            Button btnSwitch = new Button(this);
            btnSwitch.setText("Flip");
            btnSwitch.setOnClickListener(v -> {
                lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) 
                    ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
                bindCameraUseCases();
            });

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            btnParams.setMargins(10, 0, 10, 0);

            buttonLayout.addView(btnClose, btnParams);
            buttonLayout.addView(btnFlash, btnParams);
            buttonLayout.addView(btnSwitch, btnParams);

            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layoutParams.gravity = Gravity.BOTTOM;
            layoutParams.bottomMargin = 20;
            scannerOverlay.addView(buttonLayout, layoutParams);
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

    private byte[] rotateYPlane(byte[] source, int width, int height, int rotationDegrees) {
        if (rotationDegrees == 0) return source;
        byte[] rotated = new byte[source.length];
        
        if (rotationDegrees == 90) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    rotated[x * height + (height - 1 - y)] = source[y * width + x];
                }
            }
        } else if (rotationDegrees == 180) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    rotated[(height - 1 - y) * width + (width - 1 - x)] = source[y * width + x];
                }
            }
        } else if (rotationDegrees == 270) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    rotated[(width - 1 - x) * height + y] = source[y * width + x];
                }
            }
        }
        return rotated;
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            android.graphics.Rect cropRect = image.getCropRect();
            int cropWidth = cropRect.width();
            int cropHeight = cropRect.height();
            byte[] croppedYPlane = new byte[cropWidth * cropHeight];

            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            int rowStride = image.getPlanes()[0].getRowStride();
            int pixelStride = image.getPlanes()[0].getPixelStride();

            for (int row = 0; row < cropHeight; row++) {
                yBuffer.position((cropRect.top + row) * rowStride + cropRect.left * pixelStride);
                yBuffer.get(croppedYPlane, row * cropWidth, cropWidth);
            }

            int rotation = image.getImageInfo().getRotationDegrees();
            byte[] finalYPlane = rotateYPlane(croppedYPlane, cropWidth, cropHeight, rotation);
            
            int finalWidth = (rotation == 90 || rotation == 270) ? cropHeight : cropWidth;
            int finalHeight = (rotation == 90 || rotation == 270) ? cropWidth : cropHeight;

            String result = UnifiedScanner.scan(finalYPlane, finalWidth, finalHeight, finalWidth);
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
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (camera != null && camera.getCameraInfo().getZoomState().getValue() != null) {
                    float currentZoom = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                    float maxZoom = camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
                    float minZoom = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();

                    float newZoom = currentZoom + 1.5f; 
                    if (newZoom >= maxZoom) {
                        newZoom = minZoom;
                    }
                    camera.getCameraControl().setZoomRatio(newZoom);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (camera != null && camera.getCameraInfo().getZoomState().getValue() != null) {
                    float minZoom = camera.getCameraInfo().getZoomState().getValue().getMinZoomRatio();
                    camera.getCameraControl().setZoomRatio(minZoom);
                }
                return true;
            }
        });

        viewFinder.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
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
                
                // CRITICAL FIX: Ignore phantom hint text like "Type a message" when the field is empty
                if (android.os.Build.VERSION.SDK_INT >= 26 && focused.isShowingHintText()) {
                    currentText = "";
                } else if (currentText.toString().trim().equalsIgnoreCase("type a message") || 
                           currentText.toString().trim().equalsIgnoreCase("message")) {
                    // Manual fallback for apps that don't correctly flag their hints
                    currentText = "";
                }
                
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
                
                try {
                    Bundle selectionArgs = new Bundle();
                    selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, min + newText.length());
                    selectionArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, min + newText.length());
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs);
                } catch (Exception e) {
                    // Safely ignore selection targeting errors on heavily customized OEM keyboards
                }
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
