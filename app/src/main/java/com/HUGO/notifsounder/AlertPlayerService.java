package com.HUGO.notifsounder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

public class AlertPlayerService extends Service {

    private static MediaPlayer mediaPlayer;
    private static volatile boolean isLoopingAlarm = false;
    private static volatile String lastLuaResult = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground("NotifSounder Active", "Monitoring notifications");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("STOP_ACK".equals(action)) {
                stopPlayback();
                stopSelf();
                return 2;
            } else if ("PLAY".equals(action)) {
                String soundPath = intent.getStringExtra("sound_path");
                int volume = intent.getIntExtra("volume", 100);
                boolean ignoreDnd = intent.getBooleanExtra("ignore_dnd", false);
                boolean vibrate = intent.getBooleanExtra("vibrate", true);
                boolean repeatAck = intent.getBooleanExtra("repeat_ack", false);
                String ruleName = intent.getStringExtra("rule_name");
                String profName = intent.getStringExtra("profile_name");
                String notifPkg = intent.getStringExtra("notif_pkg");
                String matchedText = intent.getStringExtra("matched_text");
                String luaScript = intent.getStringExtra("lua_script");

                promoteToForeground("Alert: " + (ruleName != null ? ruleName : "Rule"),
                        repeatAck ? "Open app to Acknowledge & Stop Sound" : "Playing notification alert");

                if (luaScript != null && luaScript.trim().length() > 0) {
                    runEventScriptAsync(luaScript.trim(), notifPkg, matchedText, ruleName, profName);
                }

                playAlert(soundPath, volume, ignoreDnd, vibrate, repeatAck);
            }
        }
        return 2;
    }

    public static String getLastLuaResult() {
        return lastLuaResult;
    }

    public static void runEventScriptAsync(final String scriptOrCmd, final String notifPkg,
                                           final String notifText, final String eventName,
                                           final String profileName) {
        if (scriptOrCmd == null || scriptOrCmd.trim().length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (scriptOrCmd.toLowerCase().endsWith(".lua")) {
                        Class<?> jsePlatform = Class.forName("org.luaj.vm2.lib.jse.JsePlatform");
                        Method standardGlobals = jsePlatform.getMethod("standardGlobals");
                        Object globals = standardGlobals.invoke(null);

                        Class<?> luaValueClass = Class.forName("org.luaj.vm2.LuaValue");
                        Method setMethod = luaValueClass.getMethod("set", String.class, String.class);
                        setMethod.invoke(globals, "NOTIF_PKG", notifPkg != null ? notifPkg : "");
                        setMethod.invoke(globals, "NOTIF_TEXT", notifText != null ? notifText : "");
                        setMethod.invoke(globals, "EVENT_NAME", eventName != null ? eventName : "");
                        setMethod.invoke(globals, "PROFILE_NAME", profileName != null ? profileName : "");

                        Method loadFile = globals.getClass().getMethod("loadfile", String.class);
                        Object chunk = loadFile.invoke(globals, scriptOrCmd);
                        Method call = luaValueClass.getMethod("call");
                        Object res = call.invoke(chunk);
                        lastLuaResult = "LuaJ OK (" + scriptOrCmd + "): " + (res != null ? res.toString() : "done");
                    } else {
                        Process proc = Runtime.getRuntime().exec(scriptOrCmd);
                        InputStream is = proc.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[512];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            baos.write(buf, 0, n);
                        }
                        int code = proc.waitFor();
                        lastLuaResult = "Cmd Exit " + code + ": " + new String(baos.toByteArray(), "UTF-8").trim();
                    }
                } catch (Throwable e) {
                    lastLuaResult = "Script Error: " + e.getMessage();
                }
            }
        }).start();
    }

    public static synchronized void stopPlayback() {
        isLoopingAlarm = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
    }

    private synchronized void playAlert(String soundPath, int volumePercent, boolean ignoreDnd,
                                        boolean vibrate, final boolean repeatAck) {
        stopPlayback();
        isLoopingAlarm = repeatAck;

        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int streamType = ignoreDnd ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC;

            if (ignoreDnd && am != null) {
                try {
                    am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                } catch (Throwable ignored) {}
                if (Build.VERSION.SDK_INT >= 23) {
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        Method checkPolicy = NotificationManager.class.getMethod("isNotificationPolicyAccessGranted");
                        Boolean granted = (Boolean) checkPolicy.invoke(nm);
                        if (granted != null && granted.booleanValue()) {
                            Method setFilter = NotificationManager.class.getMethod("setInterruptionFilter", int.class);
                            setFilter.invoke(nm, Integer.valueOf(1));
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (am != null) {
                int maxVol = am.getStreamMaxVolume(streamType);
                int targetVol = (maxVol * Math.max(0, Math.min(100, volumePercent))) / 100;
                am.setStreamVolume(streamType, targetVol, 0);
            }

            if (vibrate) {
                try {
                    Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib != null) {
                        vib.vibrate(new long[] { 0, 400, 200, 400 }, -1);
                    }
                } catch (Throwable ignored) {}
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(streamType);

            boolean sourceSet = false;
            if (soundPath != null && soundPath.trim().length() > 0) {
                File f = new File(soundPath.trim());
                if (f.exists() && f.canRead()) {
                    FileInputStream fis = new FileInputStream(f);
                    try {
                        mediaPlayer.setDataSource(fis.getFD());
                        sourceSet = true;
                    } finally {
                        try { fis.close(); } catch (Exception ignored) {}
                    }
                }
            }

            if (!sourceSet) {
                Uri defUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (defUri == null) defUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                mediaPlayer.setDataSource(this, defUri);
            }

            mediaPlayer.setLooping(repeatAck);
            float volScalar = Math.max(0.0f, Math.min(1.0f, volumePercent / 100.0f));
            mediaPlayer.setVolume(volScalar, volScalar);
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    if (!repeatAck) {
                        stopPlayback();
                        stopSelf();
                    }
                }
            });
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Throwable e) {
            stopPlayback();
        }
    }

    private void promoteToForeground(String title, String text) {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23)
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

            Notification notification = null;

            if (Build.VERSION.SDK_INT >= 26) {
                String channelId = "notifsounder_channel";
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Class<?> chanClass = Class.forName("android.app.NotificationChannel");
                Object channel = chanClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(channelId, "NotifSounder Alerts", Integer.valueOf(3));
                Method createChan = NotificationManager.class.getMethod("createNotificationChannel", chanClass);
                if (nm != null) createChan.invoke(nm, channel);

                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(Context.class, String.class)
                        .newInstance(this, channelId);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, title);
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, text);
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_popup_sync));
                notification = (Notification) builderClass.getMethod("build").invoke(builder);

            } else if (Build.VERSION.SDK_INT >= 11) {
                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(Context.class).newInstance(this);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, title);
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, text);
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_popup_sync));
                if (Build.VERSION.SDK_INT >= 16) {
                    notification = (Notification) builderClass.getMethod("build").invoke(builder);
                } else {
                    notification = (Notification) builderClass.getMethod("getNotification").invoke(builder);
                }
            } else {
                notification = new Notification(android.R.drawable.ic_popup_sync, title, System.currentTimeMillis());
                Method setLatest = Notification.class.getMethod(
                        "setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                setLatest.invoke(notification, this, title, text, pendingIntent);
            }

            if (Build.VERSION.SDK_INT >= 29 && notification != null) {
                // FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
                Method startFg3 = Service.class.getMethod("startForeground", int.class, Notification.class, int.class);
                startFg3.invoke(this, Integer.valueOf(201), notification, Integer.valueOf(2));
            } else if (Build.VERSION.SDK_INT >= 5 && notification != null) {
                Method startFg2 = Service.class.getMethod("startForeground", int.class, Notification.class);
                startFg2.invoke(this, Integer.valueOf(201), notification);
            } else {
                Method setFg = Service.class.getMethod("setForeground", boolean.class);
                setFg.invoke(this, Boolean.TRUE);
            }
        } catch (Throwable ignored) {}
    }
}
