package com.example.asyncer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AsyncerService extends Service {

    // --- Pro Sync Mode Bitmasks ---
    public static final int SYNC_SKIP            = 0;
    public static final int SYNC_NEWER           = 1;
    public static final int SYNC_OLDER           = 2;
    public static final int SYNC_LARGER          = 4;
    public static final int SYNC_SMALLER         = 8;
    public static final int SYNC_ALWAYS          = 16;
    public static final int SYNC_FORCE_READONLY  = 32;
    public static final int SYNC_MOVE_FILE       = 64;
    public static final int SYNC_BACKUP_DEST     = 128;
    public static final int SYNC_CLONE_TIMES     = 256;

    private boolean isPaused = false;
    private boolean isExit = false;
    private boolean isCaptivePortal = false;
    private SharedPreferences prefs;
    private Handler uiHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("AsyncerPrefs", MODE_PRIVATE);
        uiHandler = new Handler(Looper.getMainLooper());
        
        startForegroundService();
        checkCaptivePortalThread();
        
        // Start main background loop equivalent to SyncThread[cite: 1]
        new Thread(this::syncThread).start();
    }

    private void startForegroundService() {
        String channelId = "asyncer_channel";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(channelId, "Asyncer Background", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        builder.setContentTitle("Asyncer")
               .setContentText("Sync Idle. Waiting for new files.")
               .setSmallIcon(android.R.drawable.ic_popup_sync)
               .setContentIntent(pi);

        startForeground(1, Build.VERSION.SDK_INT >= 16 ? builder.build() : builder.getNotification());
    }

    // Equivalent to CheckCaptivePortalThread[cite: 1]
    private void checkCaptivePortalThread() {
        new Thread(() -> {
            try {
                URL url = new URL("http://www.msftconnecttest.com/connecttest.txt");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line = in.readLine();
                if (line != null && line.contains("Microsoft Connect Test")) {
                    isCaptivePortal = false;
                } else {
                    isCaptivePortal = true;
                }
                conn.disconnect();
            } catch (Exception e) {
                isCaptivePortal = true;
            }
            
            if (isCaptivePortal) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.msftconnecttest.com/redirect"));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
            }
        }).start();
    }

    private boolean isSystemIdle() {
        // Android equivalent: check if screen is off
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return !pm.isInteractive();
    }

    private void showToastNonBlocking(String message) {
        uiHandler.post(() -> Toast.makeText(AsyncerService.this, message, Toast.LENGTH_SHORT).show());
    }

    private void logSyncAction(String src, String dst, long size, long lastModified) {
        String logFile = prefs.getString("LogFile", "");
        if (logFile.isEmpty()) return;
        
        try {
            File f = new File(logFile);
            FileWriter fw = new FileWriter(f, true);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            String dateStr = sdf.format(new Date(lastModified));
            fw.append(String.format(Locale.US, "[%s] SIZE: %d bytes | %s -> %s\n", dateStr, size, src, dst));
            fw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- STUBBED NETWORK LOGIC ---
    private boolean ensureNetworkConnection(String path) {
        if (path.startsWith("ftp://")) return true;
        if (path.startsWith("\\\\") || path.startsWith("smb://")) {
            // Stubbed SMB implementation as requested
            showToastNonBlocking("SMB Auth stubbed for: " + path);
            return true;
        }
        return true; 
    }

    private void syncFile(File src, File dst, int syncFlags) {
        if (src.getPath().startsWith("ftp://") || dst.getPath().startsWith("ftp://")) {
            // FTP stubbed
            showToastNonBlocking("FTP Transfer stubbed for: " + src.getName());
            return;
        }

        if (!src.exists()) {
            showToastNonBlocking("SYNC FAILED: Cannot Access Source " + src.getName());
            return;
        }

        if (dst.exists() && (syncFlags & SYNC_ALWAYS) == 0) {
            boolean overwrite = false;
            long srcTime = src.lastModified();
            long dstTime = dst.lastModified();
            long srcLen = src.length();
            long dstLen = dst.length();

            if ((syncFlags & SYNC_NEWER) != 0 && srcTime > dstTime) overwrite = true;
            if ((syncFlags & SYNC_OLDER) != 0 && srcTime < dstTime) overwrite = true;
            if ((syncFlags & SYNC_LARGER) != 0 && srcLen > dstLen) overwrite = true;
            if ((syncFlags & SYNC_SMALLER) != 0 && srcLen < dstLen) overwrite = true;
            
            if (!overwrite) return; 
        }

        File dstDir = dst.getParentFile();
        if (dstDir != null && !dstDir.exists()) dstDir.mkdirs();

        showToastNonBlocking("SYNCING: " + src.getName());

        try {
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            fis.close();
            
            if ((syncFlags & SYNC_CLONE_TIMES) != 0) dst.setLastModified(src.lastModified());
            logSyncAction(src.getPath(), dst.getPath(), src.length(), src.lastModified());
            
            if ((syncFlags & SYNC_MOVE_FILE) != 0) src.delete();
        } catch (Exception e) {
            showToastNonBlocking("SYNC FAILED: File Copy Error " + src.getName());
        }
    }

    private void syncDirectory(File srcDir, File dstDir, int syncFlags, String masks) {
        if (isExit) return;
        File[] files = srcDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (isExit) break;
            File dstFile = new File(dstDir, f.getName());

            if (f.isDirectory()) {
                syncDirectory(f, dstFile, syncFlags, masks);
            } else {
                // Simplified mask logic (C uses PathMatchSpec)[cite: 1]
                if (masks.isEmpty() || masks.contains("*.*") || f.getName().contains(masks.replace("*", ""))) {
                    syncFile(f, dstFile, syncFlags);
                }
            }
        }
    }

    private void runCommandHidden(String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        try { Runtime.getRuntime().exec(cmd); } catch (Exception e) { }
    }

    private void syncThread() {
        while (!isExit) {
            if (!isPaused) {
                int idleWait = prefs.getInt("IdleWaitSeconds", 300);
                boolean idleOnly = prefs.getBoolean("IdleOnly", false);
                
                if (!idleOnly || isSystemIdle()) {
                    String jobsStr = prefs.getString("Jobs", "");
                    String[] jobs = jobsStr.split(";;"); // using double semi-colon as job separator
                    
                    for (String job : jobs) {
                        if (job.isEmpty() || isExit || isPaused) break;
                        
                        // Parse: Src,Dst,Flags,Masks,PostCmd,IfProc,IfNotProc[cite: 1]
                        String[] parts = job.split(",");
                        if (parts.length < 2) continue;
                        
                        String src = parts[0];
                        String dst = parts[1];
                        int flags = parts.length > 2 && !parts[2].isEmpty() ? Integer.parseInt(parts[2]) : SYNC_SKIP;
                        String masks = parts.length > 3 ? parts[3] : "*.*";
                        String cmd = parts.length > 4 ? parts[4] : "";
                        // IfProc/IfNotProc omitted as Android heavily restricts process enumeration
                        
                        if (!src.isEmpty() && !dst.isEmpty() && ensureNetworkConnection(src) && ensureNetworkConnection(dst)) {
                            syncDirectory(new File(src), new File(dst), flags, masks);
                            if (!cmd.isEmpty()) runCommandHidden(cmd);
                        }
                    }
                }
            }
            
            int refresh = prefs.getInt("RefreshInterval", 3600);
            try { Thread.sleep(refresh * 1000L); } catch (InterruptedException e) {}
        }
    }

    @Override
    public void onDestroy() {
        isExit = true;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
