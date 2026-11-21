package com.example.asyncer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            if (shouldRunOnStart(context)) {
                Intent serviceIntent = new Intent(context, AsyncerService.class);
                serviceIntent.putExtra("from_boot", true);
                startServiceCompat(context, serviceIntent);
            }
        }
    }

    public static void startServiceCompat(Context context, Intent serviceIntent) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(context, serviceIntent);
                return;
            } catch (Throwable ignored) {}
        }
        context.startService(serviceIntent);
    }

    public static boolean shouldRunOnStart(Context context) {
        try {
            File iniFile = new File(context.getFilesDir(), "asyncer.ini");
            if (!iniFile.exists()) return true; // Default enabled on fresh install

            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(iniFile));
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
                        return "1".equals(val) || "true".equalsIgnoreCase(val);
                    }
                }
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
        return true;
    }
}