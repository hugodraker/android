package com.example.asyncer;

import android.app.Activity;
import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncRunner {

    public interface LogCallback {
        void onLog(String msg);
    }

    public static class IniConfig {
        public Map<String, String> global = new HashMap<String, String>();
        public Map<Integer, Map<String, String>> jobs = new HashMap<Integer, Map<String, String>>();
    }

    public static class ShellResult {
        public int exitCode;
        public String stdout;
        public String stderr;
    }

    public static IniConfig loadIniConfig(File iniFile) {
        IniConfig cfg = new IniConfig();
        if (iniFile == null || !iniFile.exists()) return cfg;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(iniFile));
            String line;
            String section = "";
            Map<String, String> curJobMap = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).toUpperCase();
                    if (section.startsWith("JOB_")) {
                        int idx = parseIntSafe(section.substring(4), -1);
                        if (idx >= 0) {
                            curJobMap = new HashMap<String, String>();
                            cfg.jobs.put(idx, curJobMap);
                        } else {
                            curJobMap = null;
                        }
                    } else {
                        curJobMap = null;
                    }
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();

                if ("GLOBAL".equals(section)) {
                    cfg.global.put(k, v);
                } else if (curJobMap != null) {
                    curJobMap.put(k, v);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
        return cfg;
    }

    public static String runJobAndScript(Context ctx, Activity activity, int jobIndex, boolean runAssociatedScript, LogCallback logger) {
        File iniFile = new File(ctx.getFilesDir(), "asyncer.ini");
        IniConfig cfg = loadIniConfig(iniFile);
        Map<String, String> job = cfg.jobs.get(jobIndex);
        if (job == null) {
            String msg = "Job " + (jobIndex + 1) + " is not configured.";
            if (logger != null) logger.onLog(msg);
            return msg;
        }

        StringBuilder summary = new StringBuilder();
        String syncResult = executeSingleJob(ctx, jobIndex, job, logger);
        summary.append(syncResult);

        if (runAssociatedScript) {
            String extCmd = job.get("ext_cmd");
            if (extCmd != null && extCmd.trim().length() > 0) {
                String scriptRes = executeCommandOrLua(ctx, activity, extCmd.trim(), job, logger);
                summary.append(" | Script: ").append(scriptRes);
            }
        }
        return summary.toString();
    }

    public static String executeSingleJob(Context ctx, int jobIndex, Map<String, String> job, LogCallback logger) {
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

        if (host == null || host.trim().length() == 0) {
            return "Job " + (jobIndex + 1) + ": skipped (no host)";
        }
        if (protocol == 0 && (remotePath == null || remotePath.trim().length() == 0)) {
            return "Job " + (jobIndex + 1) + ": skipped (no SMB share)";
        }

        File localDir = (localDirStr != null && localDirStr.trim().length() > 0)
                ? new File(localDirStr.trim())
                : new File(Environment.getExternalStorageDirectory(), "sync");
        if (!localDir.exists()) {
            try { localDir.mkdirs(); } catch (Throwable ignored) {}
        }

        if (protocol == 1) {
            int ftpPort = parseIntSafe(portStr, 21);
            String ftpRemote = (remotePath == null || remotePath.trim().length() == 0 || "PUBLIC".equalsIgnoreCase(remotePath.trim()))
                    ? "/" : remotePath.trim();
            FtpClient ftp = new FtpClient();
            if (logger != null) {
                ftp.setLogListener(new FtpClient.LogListener() {
                    public void onLog(String m) { logger.onLog(m); }
                });
            }
            try {
                if (!ftp.connect(host, ftpPort, user, pass)) {
                    String err = "Job " + (jobIndex + 1) + " FTP login failed: " + ftp.getLastReply();
                    if (logger != null) logger.onLog(err);
                    return err;
                }
                if (direction == 0) {
                    int up = syncLocalToFtp(ftp, localDir, ftpRemote, recursive, mode, deleteExtras, logger);
                    return "Job " + (jobIndex + 1) + " FTP upload: " + up + " file(s)";
                } else if (direction == 1) {
                    int dn = syncFtpToLocal(ftp, ftpRemote, localDir, recursive, mode, deleteExtras, logger);
                    return "Job " + (jobIndex + 1) + " FTP download: " + dn + " file(s)";
                } else {
                    int up = syncLocalToFtp(ftp, localDir, ftpRemote, recursive, 0, false, logger);
                    int dn = syncFtpToLocal(ftp, ftpRemote, localDir, recursive, 0, false, logger);
                    return "Job " + (jobIndex + 1) + " FTP 2-way: " + up + " up, " + dn + " down";
                }
            } catch (Exception e) {
                return "Job " + (jobIndex + 1) + " FTP error: " + e.getMessage();
            } finally {
                ftp.disconnect();
            }
        } else {
            int smbPort = parseIntSafe(portStr, 445);
            SmbClient smb = new SmbClient();
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

                if (!smb.connectServer(prof)) {
                    String err = "Job " + (jobIndex + 1) + " SMB connect failed";
                    if (logger != null) logger.onLog(err);
                    return err;
                }
                if (direction == 0) {
                    int up = syncLocalToSmb(smb, localDir, "", recursive, mode, deleteExtras, logger);
                    return "Job " + (jobIndex + 1) + " SMB upload: " + up + " file(s)";
                } else if (direction == 1) {
                    int dn = syncSmbToLocal(smb, "", localDir, recursive, mode, deleteExtras, logger);
                    return "Job " + (jobIndex + 1) + " SMB download: " + dn + " file(s)";
                } else {
                    int up = syncLocalToSmb(smb, localDir, "", recursive, 0, false, logger);
                    int dn = syncSmbToLocal(smb, "", localDir, recursive, 0, false, logger);
                    return "Job " + (jobIndex + 1) + " SMB 2-way: " + up + " up, " + dn + " down";
                }
            } catch (Exception e) {
                return "Job " + (jobIndex + 1) + " SMB error: " + e.getMessage();
            } finally {
                smb.disconnectAll();
            }
        }
    }

    public static String executeCommandOrLua(Context ctx, Activity activity, String cmdOrPath,
                                             Map<String, String> jobContext, LogCallback logger) {
        if (cmdOrPath == null || cmdOrPath.trim().length() == 0) return "No command";
        cmdOrPath = cmdOrPath.trim();
        try {
            if (cmdOrPath.toLowerCase().endsWith(".lua")) {
                if (logger != null) logger.onLog("Running LuaJ script: " + cmdOrPath);
                return LuaAndroidBridge.runLuaFile(ctx, activity, cmdOrPath, jobContext, logger);
            } else {
                if (logger != null) logger.onLog("Running shell command: " + cmdOrPath);
                ShellResult sr = runAndroidShell(ctx, cmdOrPath);
                StringBuilder res = new StringBuilder();
                res.append("exit=").append(sr.exitCode);
                if (sr.stdout.length() > 0) res.append("\n").append(sr.stdout);
                if (sr.stderr.length() > 0) res.append("\n[stderr] ").append(sr.stderr);
                String outStr = res.toString();
                if (logger != null) logger.onLog("Shell finished: " + outStr);
                return outStr;
            }
        } catch (Exception e) {
            String err = "Error: " + e.getMessage();
            if (logger != null) logger.onLog(err);
            return err;
        }
    }

    public static ShellResult runAndroidShell(Context ctx, String cmdOrScript) throws Exception {
        ShellResult sr = new ShellResult();
        String shBin = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "sh";
        String trimmed = cmdOrScript.trim();

        String[] argv;
        File maybeScript = new File(trimmed);
        if (maybeScript.exists() && maybeScript.isFile()) {
            // Normalize CRLF to LF into cacheDir so /sdcard noexec and Windows line endings work cleanly
            File tmpScript = new File(ctx.getCacheDir(), "asyncer_run.sh");
            FileInputStream fis = new FileInputStream(maybeScript);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\r') baos.write(buf[i]);
                }
            }
            fis.close();
            FileOutputStream fos = new FileOutputStream(tmpScript, false);
            fos.write(baos.toByteArray());
            fos.flush();
            fos.close();
            argv = new String[] { shBin, tmpScript.getAbsolutePath() };
        } else {
            String cleanCmd = trimmed.replace("\r\n", "\n").replace('\r', '\n');
            argv = new String[] { shBin, "-c", cleanCmd };
        }

        String[] envp = new String[] {
                "PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/su/bin",
                "TMPDIR=" + ctx.getCacheDir().getAbsolutePath()
        };

        Process proc = Runtime.getRuntime().exec(argv, envp, ctx.getFilesDir());
        sr.stdout = readAllUtf8(proc.getInputStream());
        sr.stderr = readAllUtf8(proc.getErrorStream());
        sr.exitCode = proc.waitFor();
        return sr;
    }

    private static String readAllUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        is.close();
        return baos.toString("UTF-8").trim();
    }

    private static int syncLocalToSmb(SmbClient client, File localFolder, String remoteSubPath,
                                      boolean recursive, int mode, boolean deleteExtras, LogCallback logger) {
        File[] files = localFolder.listFiles();
        if (files == null) return 0;
        if (remoteSubPath.length() > 0) client.mkdirRecursive(remoteSubPath);

        List<SmbClient.DirectoryItem> remoteItems = client.fetchRemoteDirContents(remoteSubPath);
        Map<String, SmbClient.DirectoryItem> remoteMap = new HashMap<String, SmbClient.DirectoryItem>();
        for (int i = 0; i < remoteItems.size(); i++) {
            SmbClient.DirectoryItem item = remoteItems.get(i);
            remoteMap.put(item.path.toLowerCase(), item);
        }

        int count = 0;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String remoteItemPath = remoteSubPath.length() > 0 ? (remoteSubPath + "\\" + f.getName()) : f.getName();
            if (f.isDirectory()) {
                if (recursive) count += syncLocalToSmb(client, f, remoteItemPath, true, mode, deleteExtras, logger);
            } else if (f.isFile()) {
                SmbClient.DirectoryItem existing = remoteMap.remove(remoteItemPath.toLowerCase());
                boolean shouldUpload = true;
                if (existing != null) {
                    if (mode == 2) shouldUpload = false;
                    else if (mode == 0 && existing.size == f.length() && existing.getUnixTimeMs() >= f.lastModified()) shouldUpload = false;
                }
                if (shouldUpload) {
                    if (logger != null) logger.onLog("SMB Uploading: " + remoteItemPath);
                    if (client.copySingleL2r(f.getAbsolutePath(), remoteItemPath)) count++;
                }
            }
        }

        if (deleteExtras) {
            for (Map.Entry<String, SmbClient.DirectoryItem> e : remoteMap.entrySet()) {
                SmbClient.DirectoryItem item = e.getValue();
                if (!item.isDirectory) client.deleteRemote(item.path, false);
            }
        }
        return count;
    }

    private static int syncSmbToLocal(SmbClient client, String remoteSubPath, File localFolder,
                                      boolean recursive, int mode, boolean deleteExtras, LogCallback logger) {
        if (!localFolder.exists()) localFolder.mkdirs();
        List<SmbClient.DirectoryItem> remoteItems = client.fetchRemoteDirContents(remoteSubPath);
        Map<String, Boolean> seenRelPaths = new HashMap<String, Boolean>();
        int count = 0;

        for (int i = 0; i < remoteItems.size(); i++) {
            SmbClient.DirectoryItem item = remoteItems.get(i);
            if (!recursive && item.path.indexOf('\\') >= 0) continue;
            File localDest = new File(localFolder, item.path.replace('\\', File.separatorChar));
            seenRelPaths.put(localDest.getAbsolutePath().toLowerCase(), Boolean.TRUE);

            if (item.isDirectory) {
                localDest.mkdirs();
            } else {
                boolean shouldDownload = true;
                if (localDest.exists()) {
                    if (mode == 2) shouldDownload = false;
                    else if (mode == 0 && localDest.length() == item.size && localDest.lastModified() >= item.getUnixTimeMs()) shouldDownload = false;
                }
                if (shouldDownload) {
                    String fullRemote = remoteSubPath.length() > 0 ? (remoteSubPath + "\\" + item.path) : item.path;
                    if (logger != null) logger.onLog("SMB Downloading: " + item.path);
                    if (client.copySingleR2l(fullRemote, localDest.getAbsolutePath())) count++;
                }
            }
        }
        if (deleteExtras) pruneUnseenLocalFiles(localFolder, seenRelPaths, recursive);
        return count;
    }

    private static int syncLocalToFtp(FtpClient ftp, File localFolder, String remoteBaseDir,
                                      boolean recursive, int mode, boolean deleteExtras, LogCallback logger) {
        String normBase = FtpClient.normalizeFtpPath(remoteBaseDir);
        ftp.mkdirRecursive(normBase);
        List<FtpClient.FtpItem> remoteItems = ftp.fetchRemoteDirContents(normBase, recursive);
        Map<String, FtpClient.FtpItem> remoteMap = new HashMap<String, FtpClient.FtpItem>();
        for (int i = 0; i < remoteItems.size(); i++) {
            FtpClient.FtpItem it = remoteItems.get(i);
            remoteMap.put(it.path.toLowerCase(), it);
        }
        int count = uploadLocalTreeToFtp(ftp, localFolder, normBase, "", recursive, mode, remoteMap, logger);
        if (deleteExtras) {
            for (Map.Entry<String, FtpClient.FtpItem> e : remoteMap.entrySet()) {
                FtpClient.FtpItem extra = e.getValue();
                if (!extra.isDirectory) {
                    String fullRemote = "/".equals(normBase) ? ("/" + extra.path) : (normBase + "/" + extra.path);
                    ftp.deleteRemote(fullRemote, false);
                }
            }
        }
        return count;
    }

    private static int uploadLocalTreeToFtp(FtpClient ftp, File curLocalDir, String normBase, String relPrefix,
                                            boolean recursive, int mode, Map<String, FtpClient.FtpItem> remoteMap, LogCallback logger) {
        File[] files = curLocalDir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String relPath = relPrefix.length() > 0 ? (relPrefix + "/" + f.getName()) : f.getName();
            String fullRemote = "/".equals(normBase) ? ("/" + relPath) : (normBase + "/" + relPath);
            if (f.isDirectory()) {
                if (recursive) {
                    ftp.mkdirRecursive(fullRemote);
                    count += uploadLocalTreeToFtp(ftp, f, normBase, relPath, true, mode, remoteMap, logger);
                }
            } else if (f.isFile()) {
                FtpClient.FtpItem existing = remoteMap.remove(relPath.toLowerCase());
                boolean shouldUpload = true;
                if (existing != null) {
                    if (mode == 2) shouldUpload = false;
                    else if (mode == 0 && existing.size == f.length()) shouldUpload = false;
                }
                if (shouldUpload) {
                    if (logger != null) logger.onLog("FTP Uploading: " + relPath);
                    if (ftp.uploadFile(f, fullRemote)) count++;
                }
            }
        }
        return count;
    }

    private static int syncFtpToLocal(FtpClient ftp, String remoteBaseDir, File localFolder,
                                      boolean recursive, int mode, boolean deleteExtras, LogCallback logger) {
        if (!localFolder.exists()) localFolder.mkdirs();
        String normBase = FtpClient.normalizeFtpPath(remoteBaseDir);
        List<FtpClient.FtpItem> remoteItems = ftp.fetchRemoteDirContents(normBase, recursive);
        Map<String, Boolean> seenLocalPaths = new HashMap<String, Boolean>();
        int count = 0;
        for (int i = 0; i < remoteItems.size(); i++) {
            FtpClient.FtpItem item = remoteItems.get(i);
            File localDest = new File(localFolder, item.path.replace('/', File.separatorChar));
            seenLocalPaths.put(localDest.getAbsolutePath().toLowerCase(), Boolean.TRUE);
            if (item.isDirectory) {
                localDest.mkdirs();
            } else {
                boolean shouldDownload = true;
                if (localDest.exists()) {
                    if (mode == 2) shouldDownload = false;
                    else if (mode == 0 && localDest.length() == item.size) shouldDownload = false;
                }
                if (shouldDownload) {
                    String fullRemote = "/".equals(normBase) ? ("/" + item.path) : (normBase + "/" + item.path);
                    if (logger != null) logger.onLog("FTP Downloading: " + item.path);
                    if (ftp.downloadFile(fullRemote, localDest)) count++;
                }
            }
        }
        if (deleteExtras) pruneUnseenLocalFiles(localFolder, seenLocalPaths, recursive);
        return count;
    }

    private static void pruneUnseenLocalFiles(File dir, Map<String, Boolean> seenMap, boolean recursive) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                if (recursive) pruneUnseenLocalFiles(f, seenMap, true);
            } else if (!seenMap.containsKey(f.getAbsolutePath().toLowerCase())) {
                f.delete();
            }
        }
    }

    public static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
                        }
