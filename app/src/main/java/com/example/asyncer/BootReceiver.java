package com.example.asyncer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (action.equals(Intent.ACTION_BOOT_COMPLETED) || 
            action.equals("android.intent.action.QUICKBOOT_POWERON")) {
            
            // Check if the user enabled "Run on start" in the UI
            if (shouldRunOnStart(context)) {
                Intent serviceIntent = new Intent(context, AsyncerService.class);
                
                // Android 8.0+ (API 26) requires startForegroundService for boot receivers
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(serviceIntent);
                } else {
                    // Android 1.6 through 7.1
                    context.startService(serviceIntent);
                }
            }
        }
    }

    private boolean shouldRunOnStart(Context context) {
        File iniFile = new File(context.getFilesDir(), "asyncer.ini");
        if (!iniFile.exists()) return false;
        try {
            BufferedReader br = new BufferedReader(new FileReader(iniFile));
            String line;
            boolean inGlobal = false;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.equalsIgnoreCase("[GLOBAL]")) {
                    inGlobal = true;
                } else if (line.startsWith("[")) {
                    inGlobal = false;
                } else if (inGlobal && line.toLowerCase().startsWith("run_on_start=")) {
                    String val = line.substring(line.indexOf('=') + 1).trim();
                    br.close();
                    return val.equals("1") || val.equalsIgnoreCase("true");
                }
            }
            br.close();
        } catch (Exception ignored) {}
        return false;
    }
}
