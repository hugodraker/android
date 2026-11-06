package com.hugo.caldavd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class CaldavService extends Service {
    static {
        System.loadLibrary("caldavd");
    }

    public static final String CHANNEL_ID = "CaldavServerChannel";
    public static final String ACTION_START = "com.hugo.caldavd.START";
    public static final String ACTION_STOP = "com.hugo.caldavd.STOP";
    public static final String ACTION_EXIT = "com.hugo.caldavd.EXIT";
    public static final String ACTION_STATUS_CHANGED = "com.hugo.caldavd.STATUS_CHANGED";

    public static native void nativeInit(String baseDir);
    public static native boolean nativeStartServer();
    public static native void nativeStopServer();
    public static native boolean nativeIsRunning();
    public static native int[] nativeGetConfig();
    public static native void nativeSaveConfig(int port, int httpPort, int timeoutMs, boolean anon, boolean tls);
    public static native String[] nativeGetUsers();
    public static native boolean nativeGetUserCanEditAll(String user);
    public static native void nativeSaveUser(String user, String pass, boolean canEditAll);
    public static native void nativeDeleteUser(String user);
    public static native String nativeResetPassword(String user);
    public static native String nativeGenerateAndGetRootCaPath();
    public static native boolean nativeExportPfx(String destPath);
    public static native int nativeImportIcs(String icsContent);
    public static native String nativeExportIcs();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        extractWebAssets();
        nativeInit(getFilesDir().getAbsolutePath());
    }

    private void extractWebAssets() {
        File webDir = new File(getFilesDir(), "web");
        if (!webDir.exists()) webDir.mkdirs();
        String[] files = {"calendar.html", "calendar.js", "jspdf.umd.min.js"};
        AssetManager am = getAssets();
        for (String filename : files) {
            File outFile = new File(webDir, filename);
            try (InputStream in = am.open(filename);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_START:
                    nativeStartServer();
                    break;
                case ACTION_STOP:
                    nativeStopServer();
                    break;
                case ACTION_EXIT:
                    nativeStopServer();
                    stopForeground(true);
                    stopSelf();
                    sendBroadcast(new Intent(ACTION_STATUS_CHANGED));
                    System.exit(0);
                    return START_NOT_STICKY;
            }
        }
        startForeground(1, buildNotification());
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED));
        return START_STICKY;
    }

    public Notification buildNotification() {
        boolean running = nativeIsRunning();
        int[] cfg = nativeGetConfig();
        boolean tls = cfg[4] != 0;

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent toggleIntent = new Intent(this, CaldavService.class);
        toggleIntent.setAction(running ? ACTION_STOP : ACTION_START);
        PendingIntent pToggle = PendingIntent.getService(this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent exitIntent = new Intent(this, CaldavService.class);
        exitIntent.setAction(ACTION_EXIT);
        PendingIntent pExit = PendingIntent.getService(this, 2, exitIntent, PendingIntent.FLAG_IMMUTABLE);

        String statusText = running
                ? "Running (CalDAV:" + cfg[0] + " HTTP:" + cfg[1] + " TLS:" + (tls ? "On" : "Off") + ")"
                : "Stopped";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CaldAV-D Server")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .addAction(0, running ? "Stop Server" : "Start Server", pToggle)
                .addAction(0, "Exit", pExit)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "CalDAV Server Tray Status", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
