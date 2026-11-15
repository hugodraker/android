package com.example.asyncer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
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
        promoteToForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        if (!isRunning) {
            isRunning = true;
            syncThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        executeAllSyncJobs();
                    } finally {
                        isRunning = false;
                        stopSelf();
                    }
                }
            });
            syncThread.start();
        }
        return 2; // START_NOT_STICKY
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (syncThread != null) {
            syncThread.interrupt();
        }
        super.onDestroy();
    }

    // Reflection-backed foreground service promotion for Android 1.6 (API 4) through Android 15+ (API 34+)
    private void promoteToForeground() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23)
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000) // FLAG_IMMUTABLE
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
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, "Running background sync...");
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_popup_sync));
                notification = (Notification) builderClass.getMethod("build").invoke(builder);

            } else if (Build.VERSION.SDK_INT >= 11) {
                Class<?> builderClass = Class.forName("android.app.Notification$Builder");
                Object builder = builderClass.getConstructor(android.content.Context.class).newInstance(this);
                builderClass.getMethod("setContentTitle", CharSequence.class).invoke(builder, "Asyncer");
                builderClass.getMethod("setContentText", CharSequence.class).invoke(builder, "Running background sync...");
                builderClass.getMethod("setContentIntent", PendingIntent.class).invoke(builder, pendingIntent);
                builderClass.getMethod("setSmallIcon", int.class).invoke(builder, Integer.valueOf(android.R.drawable.ic_popup_sync));
                if (Build.VERSION.SDK_INT >= 16) {
                    notification = (Notification) builderClass.getMethod("build").invoke(builder);
                } else {
                    notification = (Notification) builderClass.getMethod("getNotification").invoke(builder);
                }

            } else {
                notification = new Notification(android.R.drawable.ic_popup_sync, "Asyncer", System.currentTimeMillis());
                Method setLatest = Notification.class.getMethod(
                        "setLatestEventInfo", android.content.Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                setLatest.invoke(notification, this, "Asyncer", "Running background sync...", pendingIntent);
            }

            if (Build.VERSION.SDK_INT >= 29 && notification != null) {
                // Android 10 - 15+ (API 29 - 34+): pass FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
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

    private void executeAllSyncJobs() {
        File iniFile = new File(getFilesDir(), "asyncer.ini");
        if (!iniFile.exists()) return;

        Map<Integer, Map<String, String>> jobs = parseJobsFromIni(iniFile);
        if (jobs.isEmpty()) return;

        SmbClient smbClient = new SmbClient();
        FtpClient ftpClient = new FtpClient();

        for (Map.Entry<Integer, Map<String, String>> entry : jobs.entrySet()) {
            if (!isRunning) break;
            Map<String, String> job = entry.getValue();

            int protocol = parseIntSafe(job.get("protocol"), 0);
            String host = job.get("host");
            String portStr = job.get("port");
            String user = job.get("user");
            String pass = job.get("pass");
            String remotePath = job.get("remote_path");
            String localDirStr = job.get("local_dir");
            int direction = parseIntSafe(job.get("direction"), 0);
            int mode = parseIntSafe(job.get("mode"), 0);
            boolean recursive = !"0".equals(job.get("recursive"));
            boolean deleteExtras = "1".equals(job.get("delete_extras"));

            if (host == null || host.trim().length() == 0) continue;
            if (protocol == 0 && (remotePath == null || remotePath.trim().length() == 0)) continue;

            File localDir = (localDirStr != null && localDirStr.trim().length() > 0)
                    ? new File(localDirStr.trim())
                    : new File(Environment.getExternalStorageDirectory(), "sync");

            if (!localDir.exists()) {
                try { localDir.mkdirs(); } catch (Throwable ignored) {}
            }

            if (protocol == 1) {
                int ftpPort = parseIntSafe(portStr, 21);
                String ftpRemote = (remotePath == null || remotePath.trim().length() == 0) ? "/" : remotePath.trim();
                try {
                    if (ftpClient.connect(host, ftpPort, user, pass)) {
                        if (direction == 0) {
                            syncLocalToFtp(ftpClient, localDir, ftpRemote, recursive, mode, deleteExtras);
                        } else if (direction == 1) {
                            syncFtpToLocal(ftpClient, ftpRemote, localDir, recursive, mode, deleteExtras);
                        } else if (direction == 2) {
                            syncLocalToFtp(ftpClient, localDir, ftpRemote, recursive, 0, false);
                            syncFtpToLocal(ftpClient, ftpRemote, localDir, recursive, 0, false);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    ftpClient.disconnect();
                }
            } else {
                int smbPort = parseIntSafe(portStr, 445);
                try {
                    SmbClient.ConnectionProfile prof = new SmbClient.ConnectionProfile();
                    prof.server = host;
                    prof.host = host;
                    prof.port = smbPort;
                    prof.share = remotePath;
                    prof.user = user != null ? user : "guest";
                    prof.username = prof.user;
                    prof.pass = pass != null ? pass : "";
                    prof.password = prof.pass;

                    if (smbClient.connectServer(prof)) {
                        if (direction == 0) {
                            syncLocalToSmb(smbClient, localDir, "", recursive, mode, deleteExtras);
                        } else if (direction == 1) {
                            syncSmbToLocal(smbClient, "", localDir, recursive, mode, deleteExtras);
                        } else if (direction == 2) {
                            syncLocalToSmb(smbClient, localDir, "", recursive, 0, false);
                            syncSmbToLocal(smbClient, "", localDir, recursive, 0, false);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    smbClient.disconnectAll();
                }
            }
        }
    }

    private void syncLocalToSmb(SmbClient client, File localFolder, String remoteSubPath,
                                boolean recursive, int mode, boolean deleteExtras) {
        File[] files = localFolder.listFiles();
        if (files == null) return;

        if (remoteSubPath.length() > 0) {
            client.mkdirRecursive(remoteSubPath);
        }

        List<SmbClient.DirectoryItem> remoteItems = client.fetchRemoteDirContents(remoteSubPath);
        Map<String, SmbClient.DirectoryItem> remoteMap = new HashMap<String, SmbClient.DirectoryItem>();
        for (int i = 0; i < remoteItems.size(); i++) {
            SmbClient.DirectoryItem item = remoteItems.get(i);
            remoteMap.put(item.path.toLowerCase(), item);
        }

        for (int i = 0; i < files.length; i++) {
            if (!isRunning) break;
            File f = files[i];
            String itemName = f.getName();
            String remoteItemPath = remoteSubPath.length() > 0 ? (remoteSubPath + "\\" + itemName) : itemName;

            if (f.isDirectory()) {
                if (recursive) {
                    syncLocalToSmb(client, f, remoteItemPath, true, mode, deleteExtras);
                }
            } else if (f.isFile()) {
                SmbClient.DirectoryItem existing = remoteMap.remove(remoteItemPath.toLowerCase());
                boolean shouldUpload = true;
                if (existing != null) {
                    if (mode == 2) {
                        shouldUpload = false;
                    } else if (mode == 0 && existing.size == f.length() && existing.getUnixTimeMs() >= f.lastModified()) {
                        shouldUpload = false;
                    }
                }
                if (shouldUpload) {
                    client.copySingleL2r(f.getAbsolutePath(), remoteItemPath);
                }
            }
        }

        if (deleteExtras) {
            for (Map.Entry<String, SmbClient.DirectoryItem> e : remoteMap.entrySet()) {
                SmbClient.DirectoryItem item = e.getValue();
                if (!item.isDirectory) {
                    client.deleteRemote(item.path, false);
                }
            }
        }
    }

    private void syncSmbToLocal(SmbClient client, String remoteSubPath, File localFolder,
                                boolean recursive, int mode, boolean deleteExtras) {
        if (!localFolder.exists()) localFolder.mkdirs();
        List<SmbClient.DirectoryItem> remoteItems = client.fetchRemoteDirContents(remoteSubPath);
        Map<String, Boolean> seenRelPaths = new HashMap<String, Boolean>();

        for (int i = 0; i < remoteItems.size(); i++) {
            if (!isRunning) break;
            SmbClient.DirectoryItem item = remoteItems.get(i);
            if (!recursive && item.path.indexOf('\\') >= 0) continue;

            File localDest = new File(localFolder, item.path.replace('\\', File.separatorChar));
            seenRelPaths.put(localDest.getAbsolutePath().toLowerCase(), Boolean.TRUE);

            if (item.isDirectory) {
                localDest.mkdirs();
            } else {
                boolean shouldDownload = true;
                if (localDest.exists()) {
                    if (mode == 2) {
                        shouldDownload = false;
                    } else if (mode == 0 && localDest.length() == item.size && localDest.lastModified() >= item.getUnixTimeMs()) {
                        shouldDownload = false;
                    }
                }
                if (shouldDownload) {
                    String fullRemote = remoteSubPath.length() > 0 ? (remoteSubPath + "\\" + item.path) : item.path;
                    client.copySingleR2l(fullRemote, localDest.getAbsolutePath());
                }
            }
        }

        if (deleteExtras) {
            pruneUnseenLocalFiles(localFolder, seenRelPaths, recursive);
        }
    }

    private void syncLocalToFtp(FtpClient ftp, File localFolder, String remoteBaseDir,
                                boolean recursive, int mode, boolean deleteExtras) {
        String normBase = FtpClient.normalizeFtpPath(remoteBaseDir);
        ftp.mkdirRecursive(normBase);

        List<FtpClient.FtpItem> remoteItems = ftp.fetchRemoteDirContents(normBase, recursive);
        Map<String, FtpClient.FtpItem> remoteMap = new HashMap<String, FtpClient.FtpItem>();
        for (int i = 0; i < remoteItems.size(); i++) {
            FtpClient.FtpItem it = remoteItems.get(i);
            remoteMap.put(it.path.toLowerCase(), it);
        }

        uploadLocalTreeToFtp(ftp, localFolder, normBase, "", recursive, mode, remoteMap);

        if (deleteExtras) {
            for (Map.Entry<String, FtpClient.FtpItem> e : remoteMap.entrySet()) {
                FtpClient.FtpItem extra = e.getValue();
                if (!extra.isDirectory) {
                    String fullRemote = "/".equals(normBase) ? ("/" + extra.path) : (normBase + "/" + extra.path);
                    ftp.deleteRemote(fullRemote, false);
                }
            }
        }
    }

    private void uploadLocalTreeToFtp(FtpClient ftp, File curLocalDir, String normBase, String relPrefix,
                                      boolean recursive, int mode, Map<String, FtpClient.FtpItem> remoteMap) {
        File[] files = curLocalDir.listFiles();
        if (files == null) return;

        for (int i = 0; i < files.length; i++) {
            if (!isRunning) break;
            File f = files[i];
            String relPath = relPrefix.length() > 0 ? (relPrefix + "/" + f.getName()) : f.getName();
            String fullRemote = "/".equals(normBase) ? ("/" + relPath) : (normBase + "/" + relPath);

            if (f.isDirectory()) {
                if (recursive) {
                    ftp.mkdirRecursive(fullRemote);
                    uploadLocalTreeToFtp(ftp, f, normBase, relPath, true, mode, remoteMap);
                }
            } else if (f.isFile()) {
                FtpClient.FtpItem existing = remoteMap.remove(relPath.toLowerCase());
                boolean shouldUpload = true;
                if (existing != null) {
                    if (mode == 2) {
                        shouldUpload = false;
                    } else if (mode == 0 && existing.size == f.length()) {
                        shouldUpload = false;
                    }
                }
                if (shouldUpload) {
                    ftp.uploadFile(f, fullRemote);
                }
            }
        }
    }

    private void syncFtpToLocal(FtpClient ftp, String remoteBaseDir, File localFolder,
                                boolean recursive, int mode, boolean deleteExtras) {
        if (!localFolder.exists()) localFolder.mkdirs();
        String normBase = FtpClient.normalizeFtpPath(remoteBaseDir);
        List<FtpClient.FtpItem> remoteItems = ftp.fetchRemoteDirContents(normBase, recursive);
        Map<String, Boolean> seenLocalPaths = new HashMap<String, Boolean>();

        for (int i = 0; i < remoteItems.size(); i++) {
            if (!isRunning) break;
            FtpClient.FtpItem item = remoteItems.get(i);
            File localDest = new File(localFolder, item.path.replace('/', File.separatorChar));
            seenLocalPaths.put(localDest.getAbsolutePath().toLowerCase(), Boolean.TRUE);

            if (item.isDirectory) {
                localDest.mkdirs();
            } else {
                boolean shouldDownload = true;
                if (localDest.exists()) {
                    if (mode == 2) {
                        shouldDownload = false;
                    } else if (mode == 0 && localDest.length() == item.size) {
                        shouldDownload = false;
                    }
                }
                if (shouldDownload) {
                    String fullRemote = "/".equals(normBase) ? ("/" + item.path) : (normBase + "/" + item.path);
                    ftp.downloadFile(fullRemote, localDest);
                }
            }
        }

        if (deleteExtras) {
            pruneUnseenLocalFiles(localFolder, seenLocalPaths, recursive);
        }
    }

    private void pruneUnseenLocalFiles(File dir, Map<String, Boolean> seenMap, boolean recursive) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                if (recursive) pruneUnseenLocalFiles(f, seenMap, true);
            } else {
                if (!seenMap.containsKey(f.getAbsolutePath().toLowerCase())) {
                    f.delete();
                }
            }
        }
    }

    private Map<Integer, Map<String, String>> parseJobsFromIni(File iniFile) {
        Map<Integer, Map<String, String>> jobsMap = new HashMap<Integer, Map<String, String>>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(iniFile));
            String line;
            int currentJob = -1;
            Map<String, String> currentMap = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    String sec = line.substring(1, line.length() - 1).toUpperCase();
                    if (sec.startsWith("JOB_")) {
                        currentJob = parseIntSafe(sec.substring(4), -1);
                        if (currentJob >= 0) {
                            currentMap = new HashMap<String, String>();
                            jobsMap.put(currentJob, currentMap);
                        }
                    } else {
                        currentMap = null;
                        currentJob = -1;
                    }
                    continue;
                }

                if (currentMap != null && line.indexOf('=') > 0) {
                    int eq = line.indexOf('=');
                    String k = line.substring(0, eq).trim().toLowerCase();
                    String v = line.substring(eq + 1).trim();
                    currentMap.put(k, v);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
        return jobsMap;
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
                    }
