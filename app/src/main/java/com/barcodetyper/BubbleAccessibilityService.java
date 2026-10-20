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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

public class BubbleAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private FrameLayout bubbleView;
    private BroadcastReceiver scanReceiver;
    private WindowManager.LayoutParams params;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        bubbleView = new FrameLayout(this);
        Button btn = new Button(this);
        
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0x88000000); 
        shape.setStroke(4, 0xFFFFFFFF); 
        btn.setBackground(shape);

        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(160, 160);
        bubbleView.addView(btn, btnParams);

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 300;

        btn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false;
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        windowManager.updateViewLayout(bubbleView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            Intent intent = new Intent(BubbleAccessibilityService.this, ScannerActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubbleView, params);

        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.barcodetyper.SCAN_RESULT".equals(intent.getAction())) {
                    typeText(intent.getStringExtra("barcode"));
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, new IntentFilter("com.barcodetyper.SCAN_RESULT"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, new IntentFilter("com.barcodetyper.SCAN_RESULT"));
        }
    }

    private void typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && text != null) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bubbleView != null) windowManager.removeView(bubbleView);
        if (scanReceiver != null) unregisterReceiver(scanReceiver);
    }
}