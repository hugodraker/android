package com.example.asyncer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;

public class AsyncerService extends Service {

    private volatile boolean isRunning = false;
    private Thread syncThread;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground("Asyncer active");
        startSqlHttpServerIfEnabled();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground("Asyncer active");
        final SyncRunner.IniConfig cfg = startSqlHttpServerIfEnabled();
        final boolean keepAliveForHttp = isSqlServerEnabled(cfg);

        if (!isRunning) {
            isRunning = true;
            syncThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int i = 0; i < 5; i++) {
                            if (!isRunning) break;
                            Map<String, String> job = cfg.jobs.get(i);
                            if (job != null && job.get("host") != null && job.get("host").trim().length() > 0) {
                                SyncRunner.runJobAndScript(AsyncerService.this, null, i, true, null);
                            }
                        }
                    } finally {
                        isRunning = false;
                        if (!keepAliveForHttp) {
                            stopSelf();
                        }
                    }
                }
            });
            syncThread.start();
        }
        return keepAliveForHttp ? 1 : 2; // START_STICKY when HTTP server is active
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (syncThread != null) {
            syncThread.interrupt();
        }
        SqlHttpServer.getInstance(this).stop();
        super.onDestroy();
    }

    private SyncRunner.IniConfig startSqlHttpServerIfEnabled() {
        File iniFile = new File(getFilesDir(), "asyncer.ini");
        SyncRunner.IniConfig cfg = SyncRunner.loadIniConfig(iniFile);
        if (isSqlServerEnabled(cfg)) {
            int port = SyncRunner.parseIntSafe(cfg.global.get("http_port"), 8080);
            SqlHttpServer.getInstance(this).start(port);
        } else {
            SqlHttpServer.getInstance(this).stop();
        }
        return cfg;
    }

    private boolean isSqlServerEnabled(SyncRunner.IniConfig cfg) {
        String v = cfg.global.get("sql_server_enabled");
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private void promoteToForeground(String statusText) {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23)
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);
            Notification notification = null;

            if (Build.VERSION.SDK_INT >= 26) {
                String channelId = "asyncer_background_channel";
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Class<?> chanClass = Class.forName("android.app.NotificationChannel");
                Object channel = chanClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(channelId, "Asyncer Background Sync", Integer.valueOf(2));
                Method createChan = NotificationManager.class.getMethod("createNotificationChannel", chanClass);
                if (nm != null) createChan.invoke(nm, channel);

                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(android.content.Context.class, String.class)
                        .newInstance(this, channelId);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, "Asyncer");
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, statusText);
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_popup_sync));
                notification = (Notification) builderClass.getMethod("build").invoke(builder);
            } else if (Build.VERSION.SDK_INT >= 11) {
                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(android.content.Context.class).newInstance(this);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, "Asyncer");
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, statusText);
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_popup_sync));
                notification = (Notification) builderClass.getMethod(Build.VERSION.SDK_INT >= 16 ? "build" : "getNotification").invoke(builder);
            } else {
                notification = new Notification(android.R.drawable.ic_popup_sync, "Asyncer", System.currentTimeMillis());
                Method setLatest = Notification.class.getMethod(
                        "setLatestEventInfo", android.content.Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                setLatest.invoke(notification, this, "Asyncer", statusText, pendingIntent);
            }

            if (Build.VERSION.SDK_INT >= 29 && notification != null) {
                Method startFg3 = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                startFg3.invoke(this, Integer.valueOf(199), notification, Integer.valueOf(1));
            } else if (Build.VERSION.SDK_INT >= 5 && notification != null) {
                Method startFg2 = Service.class.getMethod("startForeground", int.class, Notification.class);
                startFg2.invoke(this, Integer.valueOf(199), notification);
            } else {
                Method setFg = Service.class.getMethod("setForeground", boolean.class);
                setFg.invoke(this, Boolean.TRUE);
            }
        } catch (Throwable ignored) {}
    }
}