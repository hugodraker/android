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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class SetupActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable the Barcode Typer Accessibility Service", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else {
            Toast.makeText(this, "Service enabled. Bubble widget is active.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            // Ignore
        }
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(getPackageName() + "/" + BubbleAccessibilityService.class.getName());
            }
        }
        return false;
    }
}