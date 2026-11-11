package com.example.asyncer;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AsyncerService extends Service {

    // --- Pro Sync Mode Bitmasks (from sync.c) ---
    public static final int SYNC_SKIP           = 0;
    public static final int SYNC_NEWER          = 1;
    public static final int SYNC_OLDER          = 2;
    public static final int SYNC_LARGER         = 4;
    public static final int SYNC_SMALLER        = 8;
    public static final int SYNC_ALWAYS         = 16;
    public static final int SYNC_FORCE_READONLY = 32;
    public static final int SYNC_MOVE_FILE      = 64;
    public static final int SYNC_BACKUP_DEST    = 128;
    public static final int SYNC_CLONE_TIMES    = 256;

    public static volatile boolean g_Paused = false;
    private volatile boolean g_Exit = false;
    public static volatile boolean g_bCaptivePortal = false;

    private final String[] g_recentLogs = new String[]{"", "", ""};
    private String g_logFile = "";
    private Handler uiHandler;

    private final SmbClient smbSrc = new SmbClient();
    private final SmbClient smbDst = new SmbClient();

    public static class UncPath {
        public String server;
        public String share;
        public String relPath;
        public String shareKey; // \\server\share
    }

    @Override
    public void onCreate() {
        super.onCreate();
        uiHandler = new Handler(Looper.getMainLooper());
        updateTrayTooltip("Sync Idle - Waiting for new files");
        checkCaptivePortalThread();
        new Thread(new Runnable() {
            @Override
            public void run() {
                syncThread();
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "CHECK_CAPTIVE".equals(intent.getAction())) {
            checkCaptivePortalThread();
        }
        updateTrayTooltip(g_Paused ? "Sync Paused" : "Sync Active");
        return START_STICKY;
    }

    public static File getIniFile(Context ctx) {
        return new File(ctx.getFilesDir(), "sync.ini");
    }

    // --- INI Parser Helper (replaces GetPrivateProfile* in sync.c) ---
    public static synchronized Map<String, Map<String, String>> readIni(File f) {
        Map<String, Map<String, String>> ini = new HashMap<String, Map<String, String>>();
        ini.put("Settings", new HashMap<String, String>());
        ini.put("Jobs", new HashMap<String, String>());
        ini.put("Credentials", new HashMap<String, String>());
        if (!f.exists()) return ini;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String section = "Settings";
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    if (!ini.containsKey(section)) {
                        ini.put(section, new HashMap<String, String>());
                    }
                } else {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        Map<String, String> secMap = ini.get(section);
                        if (secMap != null) secMap.put(k, v);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
        return ini;
    }

    public static synchronized void writeIni(File f, Map<String, Map<String, String>> ini) {
        FileWriter fw = null;
        try {
            fw = new FileWriter(f, false);
            for (Map.Entry<String, Map<String, String>> sec : ini.entrySet()) {
                fw.write("[" + sec.getKey() + "]\n");
                for (Map.Entry<String, String> kv : sec.getValue().entrySet()) {
                    fw.write(kv.getKey() + "=" + kv.getValue() + "\n");
                }
                fw.write("\n");
            }
        } catch (Exception ignored) {
        } finally {
            if (fw != null) try { fw.close(); } catch (Exception ignored) {}
        }
    }

    // --- Notifications & Non-Blocking Alerts ---
    private void updateTrayTooltip(String text) {
        String channelId = "asyncer_channel";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(channelId, "Asyncer Status", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        builder.setContentTitle("Asyncer")
               .setContentText(text)
               .setSmallIcon(android.R.drawable.ic_popup_sync)
               .setContentIntent(pi)
               .setOngoing(true);

        if (Build.VERSION.SDK_INT >= 16) {
            builder.setStyle(new Notification.BigTextStyle().bigText(text));
            startForeground(1, builder.build());
        } else {
            startForeground(1, builder.getNotification());
        }
    }

    private void addToRecentLogs(String filename) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        String shortFile = filename.length() > 18 ? "..." + filename.substring(filename.length() - 15) : filename;
        String newLog = "[" + sdf.format(new Date()) + "] " + shortFile;
        g_recentLogs[2] = g_recentLogs[1];
        g_recentLogs[1] = g_recentLogs[0];
        g_recentLogs[0] = newLog;
    }

    private void updateTrayTooltipDone() {
        if (g_recentLogs[0].isEmpty()) {
            updateTrayTooltip("Sync Idle\nWaiting for new files");
        } else {
            StringBuilder sb = new StringBuilder("Recent Syncs:");
            for (int i = 0; i < 3; i++) {
                if (!g_recentLogs[i].isEmpty()) {
                    sb.append("\n").append(g_recentLogs[i]);
                }
            }
            updateTrayTooltip(sb.toString());
        }
    }

    private void showErrorNonBlocking(final String path, final String reason) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(AsyncerService.this, "SYNC FAILED: " + path + " | " + (reason != null ? reason : "UNKNOWN"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSyncTextNonBlocking(final String text) {
        updateTrayTooltip("SYNCING: " + text);
    }

    private void logSyncAction(String src, String dst, long size, long lastModifiedMs) {
        if (g_logFile == null || g_logFile.isEmpty()) return;
        FileWriter fw = null;
        try {
            fw = new FileWriter(new File(g_logFile), true);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            fw.write(String.format(Locale.US, "[%s] SIZE: %d bytes | %s -> %s\n",
                    sdf.format(new Date(lastModifiedMs)), size, src, dst));
        } catch (Exception ignored) {
        } finally {
            if (fw != null) try { fw.close(); } catch (Exception ignored) {}
        }
    }

    // --- Captive Portal Check (from sync.c) ---
    public void checkCaptivePortalThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("http://www.msftconnecttest.com/connecttest.txt");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setUseCaches(false);

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String buf = in.readLine();
                    in.close();
                    g_bCaptivePortal = (buf == null || !buf.contains("Microsoft Connect Test"));
                    conn.disconnect();
                } catch (Exception e) {
                    g_bCaptivePortal = true;
                }

                if (g_bCaptivePortal) {
                    try {
                        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.msftconnecttest.com/redirect"));
                        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(browser);
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    // --- Path & SMB Connection Helpers ---
    public static boolean isSmbPath(String path) {
        if (path == null) return false;
        return path.startsWith("\\\\") || path.toLowerCase().startsWith("smb://");
    }

    public static UncPath parseUncPath(String rawPath) {
        if (!isSmbPath(rawPath)) return null;
        String clean = rawPath;
        if (clean.toLowerCase().startsWith("smb://")) {
            clean = clean.substring(6);
        }
        clean = clean.replace('/', '\\');
        while (clean.startsWith("\\")) clean = clean.substring(1);

        int firstSlash = clean.indexOf('\\');
        if (firstSlash < 0) return null;

        UncPath u = new UncPath();
        u.server = clean.substring(0, firstSlash);
        String rem = clean.substring(firstSlash + 1);
        int secondSlash = rem.indexOf('\\');
        if (secondSlash < 0) {
            u.share = rem;
            u.relPath = "";
        } else {
            u.share = rem.substring(0, secondSlash);
            u.relPath = rem.substring(secondSlash + 1);
        }
        u.shareKey = "\\\\" + u.server + "\\" + u.share;
        return u;
    }

    private boolean ensureSmbConnected(SmbClient client, UncPath unc, Map<String, String> credsMap) {
        if (unc == null) return false;
        if (client.isConnectedTo(unc.server, unc.share)) return true;

        SmbClient.ConnectionProfile prof = new SmbClient.ConnectionProfile();
        prof.server = unc.server;
        prof.share = unc.share;
        prof.port = "445";
        prof.protoPref = SmbClient.PROTO_AUTO;

        String credStr = credsMap.get(unc.shareKey);
        if (credStr == null) {
            for (Map.Entry<String, String> entry : credsMap.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(unc.shareKey)) {
                    credStr = entry.getValue();
                    break;
                }
            }
        }
        if (credStr != null && !credStr.isEmpty()) {
            int comma = credStr.indexOf(',');
            if (comma >= 0) {
                prof.user = credStr.substring(0, comma);
                prof.pass = credStr.substring(comma + 1);
            } else {
                prof.user = credStr;
            }
        }

        if (client.connectServer(prof)) {
            return true;
        }
        showErrorNonBlocking(unc.shareKey, "Network Auth Failed");
        return false;
    }

    private boolean matchesMasks(String fileName, String masks) {
        if (masks == null || masks.isEmpty() || masks.equals("*.*") || masks.equals("*")) return true;
        String[] parts = masks.replace(',', ';').split(";");
        String lowerName = fileName.toLowerCase(Locale.US);
        for (String mask : parts) {
            mask = mask.trim().toLowerCase(Locale.US);
            if (mask.isEmpty()) continue;
            if (mask.equals("*.*") || mask.equals("*")) return true;
            String regex = "^" + mask.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
            if (lowerName.matches(regex)) return true;
        }
        return false;
    }

    private boolean shouldOverwrite(long srcTimeMs, long dstTimeMs, long srcSize, long dstSize, int syncFlags) {
        if ((syncFlags & SYNC_ALWAYS) != 0) return true;
        boolean overwrite = false;
        // 2-second tolerance window for SMB/FAT timestamp granularity
        if ((syncFlags & SYNC_NEWER) != 0 && (srcTimeMs - dstTimeMs > 2000)) overwrite = true;
        if ((syncFlags & SYNC_OLDER) != 0 && (dstTimeMs - srcTimeMs > 2000)) overwrite = true;
        if ((syncFlags & SYNC_LARGER) != 0 && srcSize > dstSize) overwrite = true;
        if ((syncFlags & SYNC_SMALLER) != 0 && srcSize < dstSize) overwrite = true;
        return overwrite;
    }

    private boolean copyLocalFileWithThrottle(File src, File dst, int maxDiskMb) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(src);
            fos = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int len;
            long bytesWindow = 0;
            long windowStart = System.currentTimeMillis();

            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
                if (maxDiskMb > 0) {
                    bytesWindow += len;
                    long maxBytesPerSec = maxDiskMb * 1048576L;
                    if (bytesWindow >= maxBytesPerSec) {
                        long elapsed = System.currentTimeMillis() - windowStart;
                        if (elapsed < 1000) {
                            try { Thread.sleep(1000 - elapsed); } catch (InterruptedException ignored) {}
                        }
                        bytesWindow = 0;
                        windowStart = System.currentTimeMillis();
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            if (fis != null) try { fis.close(); } catch (Exception ignored) {}
        }
    }

// --- Core File & Directory Sync (Local, FTP, SMB1, SMB2) ---
    private void syncSingleFile(
            String srcFull, String dstFull,
            boolean isSrcSmb, UncPath srcUnc, SmbClient.DirectoryItem srcSmbMeta,
            boolean isDstSmb, UncPath dstUnc, SmbClient.DirectoryItem dstSmbMeta,
            int syncFlags, int maxDiskMb) {

        // 1. Check FTP URLs
        FtpClient ftpSrc = FtpClient.parseUrl(srcFull);
        FtpClient ftpDst = FtpClient.parseUrl(dstFull);
        if (ftpSrc != null || ftpDst != null) {
            String targetPath = (ftpSrc != null ? ftpSrc.path : ftpDst.path);
            String shortName = new File(targetPath != null ? targetPath : "").getName();
            showSyncTextNonBlocking(shortName);
            boolean ok = false;
            if (ftpSrc != null && ftpDst == null && !isDstSmb) {
                ok = ftpSrc.downloadFile(new File(dstFull));
            } else if (ftpDst != null && ftpSrc == null && !isSrcSmb) {
                ok = ftpDst.uploadFile(new File(srcFull));
            }
            if (ok) addToRecentLogs(shortName);
            else showErrorNonBlocking(srcFull, "FTP Transfer Failed");
            return;
        }

        // 2. Gather Source Metadata
        long srcTimeMs = 0;
        long srcSize = 0;
        String fileName;

        if (isSrcSmb) {
            if (srcSmbMeta == null) return;
            srcTimeMs = srcSmbMeta.getUnixTimeMs();
            srcSize = srcSmbMeta.size;
            fileName = srcSmbMeta.path;
        } else {
            File sf = new File(srcFull);
            if (!sf.exists() || !sf.isFile()) {
                showErrorNonBlocking(srcFull, "Cannot Access Source");
                return;
            }
            srcTimeMs = sf.lastModified();
            srcSize = sf.length();
            fileName = sf.getName();
        }

        // 3. Check Destination Metadata & Bitmasks
        boolean dstExists = false;
        long dstTimeMs = 0;
        long dstSize = 0;

        if (isDstSmb) {
            if (dstSmbMeta != null) {
                dstExists = true;
                dstTimeMs = dstSmbMeta.getUnixTimeMs();
                dstSize = dstSmbMeta.size;
            }
        } else {
            File df = new File(dstFull);
            if (df.exists() && df.isFile()) {
                dstExists = true;
                dstTimeMs = df.lastModified();
                dstSize = df.length();
            }
        }

        if (dstExists && !shouldOverwrite(srcTimeMs, dstTimeMs, srcSize, dstSize, syncFlags)) {
            return;
        }

        // 4. Handle SYNC_BACKUP_DEST (.bak) & SYNC_FORCE_READONLY
        if (dstExists && (syncFlags & SYNC_BACKUP_DEST) != 0) {
            if (isDstSmb) {
                smbDst.deleteRemote(dstUnc.relPath + ".bak", false);
                smbDst.renameRemote(dstUnc.relPath, dstUnc.relPath + ".bak");
            } else {
                File df = new File(dstFull);
                File bak = new File(dstFull + ".bak");
                if (bak.exists()) bak.delete();
                df.renameTo(bak);
            }
        }

        if (dstExists && !isDstSmb && (syncFlags & SYNC_FORCE_READONLY) != 0) {
            File df = new File(dstFull);
            if (!df.canWrite()) df.setWritable(true);
        }

        showSyncTextNonBlocking(fileName);

        // 5. Perform Transfer (Local<->Local, Local<->SMB, SMB<->SMB)
        boolean success = false;
        if (!isSrcSmb && !isDstSmb) {
            success = copyLocalFileWithThrottle(new File(srcFull), new File(dstFull), maxDiskMb);
            if (success && (syncFlags & SYNC_CLONE_TIMES) != 0) {
                new File(dstFull).setLastModified(srcTimeMs);
            }
        } else if (!isSrcSmb && isDstSmb) {
            String parentRel = getParentRelPath(dstUnc.relPath);
            if (!parentRel.isEmpty()) smbDst.mkdirRecursive(parentRel);
            success = smbDst.copySingleL2r(srcFull, dstUnc.relPath);
            if (success && (syncFlags & SYNC_CLONE_TIMES) != 0) {
                smbDst.setRemoteFileTime(dstUnc.relPath, srcTimeMs);
            }
        } else if (isSrcSmb && !isDstSmb) {
            File df = new File(dstFull);
            File parent = df.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            success = smbSrc.copySingleR2l(srcUnc.relPath, dstFull);
            if (success && (syncFlags & SYNC_CLONE_TIMES) != 0) {
                df.setLastModified(srcTimeMs);
            }
        } else {
            // SMB to SMB via temp relay file
            File temp = new File(getCacheDir(), "smb_relay.tmp");
            if (smbSrc.copySingleR2l(srcUnc.relPath, temp.getAbsolutePath())) {
                String parentRel = getParentRelPath(dstUnc.relPath);
                if (!parentRel.isEmpty()) smbDst.mkdirRecursive(parentRel);
                success = smbDst.copySingleL2r(temp.getAbsolutePath(), dstUnc.relPath);
                if (success && (syncFlags & SYNC_CLONE_TIMES) != 0) {
                    smbDst.setRemoteFileTime(dstUnc.relPath, srcTimeMs);
                }
            }
            temp.delete();
        }

        if (success) {
            logSyncAction(srcFull, dstFull, srcSize, srcTimeMs);
            addToRecentLogs(fileName);
            if ((syncFlags & SYNC_MOVE_FILE) != 0) {
                if (isSrcSmb) smbSrc.deleteRemote(srcUnc.relPath, false);
                else new File(srcFull).delete();
            }
        } else {
            showErrorNonBlocking(srcFull, "File Copy Error");
        }
    }

    private static String getParentRelPath(String relPath) {
        if (relPath == null) return "";
        String clean = SmbClient.normalizePath(relPath);
        int lastSlash = clean.lastIndexOf('\\');
        return lastSlash > 0 ? clean.substring(0, lastSlash) : "";
    }

    private static String joinSmbRel(String base, String child) {
        if (base == null || base.isEmpty() || base.equals("\\")) return child;
        return base.endsWith("\\") ? base + child : base + "\\" + child;
    }

    private void syncDirectory(String srcDir, String dstDir, int syncFlags, String masks, int maxDiskMb, Map<String, String> creds) {
        if (g_Exit || g_Paused) return;

        // Direct single-file FTP transfer check
        if (srcDir.toLowerCase().startsWith("ftp://") || dstDir.toLowerCase().startsWith("ftp://")) {
            syncSingleFile(srcDir, dstDir, false, null, null, false, null, null, syncFlags, maxDiskMb);
            return;
        }

        boolean isSrcSmb = isSmbPath(srcDir);
        boolean isDstSmb = isSmbPath(dstDir);
        UncPath srcUnc = isSrcSmb ? parseUncPath(srcDir) : null;
        UncPath dstUnc = isDstSmb ? parseUncPath(dstDir) : null;

        if (isSrcSmb && !ensureSmbConnected(smbSrc, srcUnc, creds)) return;
        if (isDstSmb && !ensureSmbConnected(smbDst, dstUnc, creds)) return;

        // Pre-fetch destination SMB directory map for fast timestamp/size comparisons
        Map<String, SmbClient.DirectoryItem> dstItemMap = new HashMap<String, SmbClient.DirectoryItem>();
        if (isDstSmb) {
            smbDst.mkdirRecursive(dstUnc.relPath);
            List<SmbClient.DirectoryItem> dstItems = smbDst.fetchRemoteDirContents("\\" + dstUnc.relPath);
            for (SmbClient.DirectoryItem item : dstItems) {
                if (!item.path.equals(".") && !item.path.equals("..")) {
                    dstItemMap.put(item.path.toLowerCase(Locale.US), item);
                }
            }
        }

        if (isSrcSmb) {
            List<SmbClient.DirectoryItem> srcItems = smbSrc.fetchRemoteDirContents("\\" + srcUnc.relPath);
            for (SmbClient.DirectoryItem item : srcItems) {
                if (g_Exit || g_Paused) break;
                if (item.path.equals(".") || item.path.equals("..")) continue;

                String nextSrc = srcDir.endsWith("\\") || srcDir.endsWith("/") ? srcDir + item.path : srcDir + "\\" + item.path;
                String nextDst = dstDir.endsWith("\\") || dstDir.endsWith("/") ? dstDir + item.path : dstDir + (isDstSmb ? "\\" : "/") + item.path;

                if (item.isDir) {
                    syncDirectory(nextSrc, nextDst, syncFlags, masks, maxDiskMb, creds);
                } else if (matchesMasks(item.path, masks)) {
                    UncPath fileSrcUnc = parseUncPath(nextSrc);
                    UncPath fileDstUnc = isDstSmb ? parseUncPath(nextDst) : null;
                    SmbClient.DirectoryItem dstMeta = isDstSmb ? dstItemMap.get(item.path.toLowerCase(Locale.US)) : null;
                    syncSingleFile(nextSrc, nextDst, true, fileSrcUnc, item, isDstSmb, fileDstUnc, dstMeta, syncFlags, maxDiskMb);
                }
            }
        } else {
            File localSrcDir = new File(srcDir);
            File[] files = localSrcDir.listFiles();
            if (files == null) return;

            for (File f : files) {
                if (g_Exit || g_Paused) break;
                String name = f.getName();
                if (name.equals(".") || name.equals("..")) continue;

                String nextSrc = f.getAbsolutePath();
                String nextDst = isDstSmb
                        ? (dstDir.endsWith("\\") || dstDir.endsWith("/") ? dstDir + name : dstDir + "\\" + name)
                        : new File(dstDir, name).getAbsolutePath();

                if (f.isDirectory()) {
                    syncDirectory(nextSrc, nextDst, syncFlags, masks, maxDiskMb, creds);
                } else if (matchesMasks(name, masks)) {
                    UncPath fileDstUnc = isDstSmb ? parseUncPath(nextDst) : null;
                    SmbClient.DirectoryItem dstMeta = isDstSmb ? dstItemMap.get(name.toLowerCase(Locale.US)) : null;
                    syncSingleFile(nextSrc, nextDst, false, null, null, isDstSmb, fileDstUnc, dstMeta, syncFlags, maxDiskMb);
                }
            }
        }
    }

    // --- System Idle, Process Check & Command Execution (from sync.c) ---
    private boolean isSystemIdle() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return true;
        return Build.VERSION.SDK_INT >= 20 ? !pm.isInteractive() : !pm.isScreenOn();
    }

    private boolean isProcessRunning(String procName) {
        if (procName == null || procName.isEmpty()) return false;
        String target = procName.toLowerCase(Locale.US);
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo info : procs) {
                    if (info.processName != null && info.processName.toLowerCase(Locale.US).contains(target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void runCommandHidden(String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        try {
            Runtime.getRuntime().exec(cmd);
        } catch (Exception ignored) {}
    }

    private static int parseIntSafe(String val, int def) {
        if (val == null || val.isEmpty()) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void syncThread() {
        while (!g_Exit) {
            int waitSecs = 3600;
            try {
                Map<String, Map<String, String>> ini = readIni(getIniFile(this));
                Map<String, String> settings = ini.get("Settings");
                Map<String, String> jobs = ini.get("Jobs");
                Map<String, String> creds = ini.get("Credentials");

                waitSecs = parseIntSafe(settings.get("RefreshInterval"), 3600);
                if (waitSecs < 5) waitSecs = 5;

                if (!g_Paused) {
                    g_logFile = settings.get("LogFile") != null ? settings.get("LogFile") : "";
                    int maxDiskMb = parseIntSafe(settings.get("MaxDiskMBps"), 0);
                    int idleOnly = parseIntSafe(settings.get("IdleOnly"), 0);

                    if (idleOnly == 0 || isSystemIdle()) {
                        for (Map.Entry<String, String> jobEntry : jobs.entrySet()) {
                            if (g_Exit || g_Paused) break;
                            String val = jobEntry.getValue();
                            if (val == null || val.isEmpty()) continue;

                            // JobName=Src,Dst,Flags,Masks,PostCmd,IfProcExists,IfProcNotExists
                            String[] tok = val.split(",", -1);
                            String src = tok.length > 0 ? tok[0].trim() : "";
                            String dst = tok.length > 1 ? tok[1].trim() : "";
                            String tok3 = tok.length > 2 ? tok[2].trim() : "";
                            String tok4 = tok.length > 3 ? tok[3].trim() : "";
                            String postCmd = tok.length > 4 ? tok[4].trim() : "";
                            String ifProc = tok.length > 5 ? tok[5].trim() : "";
                            String ifNotProc = tok.length > 6 ? tok[6].trim() : "";

                            int syncFlags = SYNC_SKIP;
                            String masks = "*.*";

                            if (!tok3.isEmpty()) {
                                if (tok3.contains("*") || tok3.contains("?")) {
                                    masks = tok3 + (!tok4.isEmpty() ? ";" + tok4 : "");
                                } else if (Character.isDigit(tok3.charAt(0))) {
                                    syncFlags = parseIntSafe(tok3, SYNC_SKIP);
                                    masks = !tok4.isEmpty() ? tok4 : "*.*";
                                } else {
                                    masks = !tok4.isEmpty() ? tok4 : "*.*";
                                }
                            } else if (!tok4.isEmpty()) {
                                masks = tok4;
                            }

                            boolean shouldRun = true;
                            if (!ifProc.isEmpty() && !isProcessRunning(ifProc)) shouldRun = false;
                            if (!ifNotProc.isEmpty() && isProcessRunning(ifNotProc)) shouldRun = false;

                            if (shouldRun && !src.isEmpty() && !dst.isEmpty()) {
                                syncDirectory(src, dst, syncFlags, masks, maxDiskMb, creds);
                                if (!postCmd.isEmpty()) runCommandHidden(postCmd);
                            }
                        }
                        smbSrc.disconnectAll();
                        smbDst.disconnectAll();
                        updateTrayTooltipDone();
                    }
                }
            } catch (Exception ignored) {}

            for (int i = 0; i < waitSecs && !g_Exit; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        smbSrc.disconnectAll();
        smbDst.disconnectAll();
    }

    @Override
    public void onDestroy() {
        g_Exit = true;
        smbSrc.disconnectAll();
        smbDst.disconnectAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
