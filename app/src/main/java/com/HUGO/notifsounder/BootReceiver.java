package com.HUGO.notifsounder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        ConfigStore store = new ConfigStore();
        store.load(context);
        if (store.runOnStart) {
            Intent svc = new Intent(context, AlertPlayerService.class);
            svc.putExtra("action", "IDLE_START");
            startServiceCompat(context, svc);
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
}
