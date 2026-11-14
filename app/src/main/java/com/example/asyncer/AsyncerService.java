package com.example.asyncer;

import android.app.Notification;
import android.app.NotificationChannel;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AsyncerService extends Service {

    private volatile boolean isRunning = false;
    private Thread syncThread;

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            syncThread = new Thread(new Runnable() {
                public void run() {
                    executeAllSyncJobs();
                    stopSelf(); // Shut down service once jobs finish
                }
            });
            syncThread.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (syncThread != null) {
            syncThread.interrupt();
        }
        super.onDestroy();
    }

    private void promoteToForeground() {
        try {
            String channelId = "asyncer_background_channel";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "Asyncer Background Sync",
                        NotificationManager.IMPORTANCE_LOW
                );
                if (nm != null) nm.createNotificationChannel(channel);
            }

            Intent notificationIntent = new Intent(this, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23) ? 
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000 /* FLAG_IMMUTABLE */ : 
                    PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new Notification.Builder(this, channelId);
            } else {
                builder = new Notification.Builder(this);
            }

            builder.setContentTitle("Asyncer")
                   .setContentText("Running background synchronization...")
                   .setContentIntent(pendingIntent)
                   .setSmallIcon(android.R.drawable.ic_popup_sync);

            Notification notification = (Build.VERSION.SDK_INT >= 16) ? builder.build() : builder.getNotification();
            startForeground(199, notification);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // MULTI-JOB INI PARSER & SYNCHRONIZATION WORKER
    // =========================================================================

    private void executeAllSyncJobs() {
        File iniFile = new File(getFilesDir(), "asyncer.ini");
        if (!iniFile.exists()) return;

        Map<Integer, Map<String, String>> jobs = parseJobsFromIni(iniFile);
        if (jobs.isEmpty()) return;

        SmbClient smbClient = new SmbClient();

        for (Map.Entry<Integer, Map<String, String>> entry : jobs.entrySet()) {
            if (!isRunning) break;
            Map<String, String> job = entry.getValue();
            
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

            if (host == null || host.length() == 0 || remotePath == null || remotePath.length() == 0) {
                continue; // Skip unconfigured jobs
            }

            File localDir = (localDirStr != null && localDirStr.length() > 0) ? 
                    new File(localDirStr) : 
                    new File(Environment.getExternalStorageDirectory(), "sync");

            if (!localDir.exists()) {
                localDir.mkdirs();
            }

            int port = parseIntSafe(portStr, 445);

            try {
                SmbClient.ConnectionProfile prof = new SmbClient.ConnectionProfile();
                prof.server = host;
                prof.host = host;
                prof.port = port;
                prof.share = remotePath;
                prof.user = user != null ? user : "guest";
                prof.username = prof.user;
                prof.pass = pass != null ? pass : "";
                prof.password = prof.pass;

                if (smbClient.connectServer(prof)) {
                    if (direction == 0) {
                        // Local -> Remote
                        syncLocalToRemote(smbClient, localDir, "", recursive, mode, deleteExtras);
                    } else if (direction == 1) {
                        // Remote -> Local
                        syncRemoteToLocal(smbClient, "", localDir, recursive, mode, deleteExtras);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                smbClient.disconnectAll();
            }
        }
    }

    private Map<Integer, Map<String, String>> parseJobsFromIni(File iniFile) {
        Map<Integer, Map<String, String>> jobsMap = new HashMap<Integer, Map<String, String>>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(iniFile));
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

                if (currentMap != null && line.contains("=")) {
                    int eq = line.indexOf('=');
                    String k = line.substring(0, eq).trim().toLowerCase();
                    String v = line.substring(eq + 1).trim();
                    currentMap.put(k, v);
                }
            }
            br.close();
        } catch (Exception ignored) {}
        return jobsMap;
    }

    // =========================================================================
    // SYNCHRONIZATION ENGINES (Local <-> SMB Remote)
    // =========================================================================

    private void syncLocalToRemote(SmbClient client, File localFolder, String remoteSubPath, boolean recursive, int mode, boolean deleteExtras) {
        File[] files = localFolder.listFiles();
        if (files == null) return;

        if (remoteSubPath.length() > 0) {
            client.mkdirRecursive(remoteSubPath);
        }

        // Fetch remote listing if mirror / delete extras is enabled
        List<SmbClient.DirectoryItem> remoteItems = deleteExtras ? client.fetchRemoteDirContents(remoteSubPath) : new ArrayList<SmbClient.DirectoryItem>();
        Map<String, SmbClient.DirectoryItem> remoteMap = new HashMap<String, SmbClient.DirectoryItem>();
        for (SmbClient.DirectoryItem item : remoteItems) {
            remoteMap.put(item.path.toLowerCase(), item);
        }

        for (File f : files) {
            if (!isRunning) break;
            String itemName = f.getName();
            String remoteItemPath = remoteSubPath.length() > 0 ? (remoteSubPath + "\\" + itemName) : itemName;

            if (f.isDirectory()) {
                if (recursive) {
                    syncLocalToRemote(client, f, remoteItemPath, recursive, mode, deleteExtras);
                }
            } else if (f.isFile()) {
                remoteMap.remove(remoteItemPath.toLowerCase());
                boolean shouldUpload = true;

                if (mode == 0) {
                    // Update: check if remote exists and compare size/time (stubbed or basic comparison)
                    // If exists and same size, skip
                } else if (mode == 2) {
                    // Skip existing
                    // Check existence on remote
                }

                if (shouldUpload) {
                    client.copySingleL2r(f.getAbsolutePath(), remoteItemPath);
                }
            }
        }

        // Delete extra files on remote if mirror mode is active
        if (deleteExtras) {
            for (Map.Entry<String, SmbClient.DirectoryItem> entry : remoteMap.entrySet()) {
                SmbClient.DirectoryItem item = entry.getValue();
                client.deleteRemote(item.path, true);
            }
        }
    }

    private void syncRemoteToLocal(SmbClient client, String remoteSubPath, File localFolder, boolean recursive, int mode, boolean deleteExtras) {
        List<SmbClient.DirectoryItem> remoteItems = client.fetchRemoteDirContents(remoteSubPath);
        if (!localFolder.exists()) {
            localFolder.mkdirs();
        }

        Map<String, File> localFileMap = new HashMap<String, File>();
        if (deleteExtras) {
            File[] localFiles = localFolder.listFiles();
            if (localFiles != null) {
                for (File lf : localFiles) {
                    localFileMap.put(lf.getName().toLowerCase(), lf);
                }
            }
        }

        for (SmbClient.DirectoryItem item : remoteItems) {
            if (!isRunning) break;
            File localDest = new File(localFolder, item.name);
            localFileMap.remove(item.name.toLowerCase());

            if (item.isDirectory) {
                if (recursive) {
                    syncRemoteToLocal(client, item.path, localDest, recursive, mode, deleteExtras);
                }
            } else {
                boolean shouldDownload = true;
                if (mode == 2 && localDest.exists()) {
                    shouldDownload = false;
                }
                if (shouldDownload) {
                    client.copySingleR2l(item.path, localDest.getAbsolutePath());
                }
            }
        }

        if (deleteExtras) {
            for (Map.Entry<String, File> entry : localFileMap.entrySet()) {
                deleteRecursiveLocal(entry.getValue());
            }
        }
    }

    private void deleteRecursiveLocal(File f) {
        if (f.isDirectory()) {
            File[] subs = f.listFiles();
            if (subs != null) {
                for (File sub : subs) deleteRecursiveLocal(sub);
            }
        }
        f.delete();
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
