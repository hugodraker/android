package com.example.asyncer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SmbClient {

    // =========================================================================
    // CONSTANTS & TYPES EXPECTED BY AsyncerService.java
    // =========================================================================
    public static final int PROTO_AUTO = 0;
    public static final int PROTO_SMB1 = 1;
    public static final int PROTO_SMB2 = 2;

    public static final int REQ_PICK_LUA = 9001;

    public static class ConnectionProfile {
        public String host = "";
        public int port = 445;
        public String share = "";
        public String domain = "";
        public String username = "";
        public String password = "";
        public int protoPref = PROTO_AUTO;
        public int timeoutMs = 5000;
    }

    public static class DirectoryItem {
        public String name = "";
        public String path = "";
        public boolean isDirectory = false;
        public boolean isDir = false;
        public long size = 0;
        public long lastModified = 0;
        public long creationTime = 0;
        public int attributes = 0;
    }

    // =========================================================================
    // INSTANCE STATE FOR SMB CONNECTION
    // =========================================================================
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private ConnectionProfile currentProfile;
    private boolean connected = false;

    public boolean isSmb2 = false;
    private long smb2SessionId = 0;
    private int smb2TreeId = 0;
    private long smb2MsgId = 0;

    private int smb1Uid = 0;
    private int smb1Tid = 0;
    private int smb1Mid = 1;

    // =========================================================================
    // UI CONTROLS & INI STATE (Share Dropdown, Probe Button, LuaJ Runner)
    // =========================================================================
    public EditText etHost, etPort, etUser, etPass, etExternalCmd;
    public Spinner spShares;
    public Button btnTestShares, btnBrowseLua, btnRunCmd;
    public TextView tvStatus;

    private Activity activity;
    private File iniFile;
    private Handler uiHandler;
    private ArrayAdapter<String> shareAdapter;
    private final List<String> shareList = new ArrayList<String>();

    public SmbClient() {
        disableStrictModeIfPresent();
    }

    public SmbClient(Activity act, File ini) {
        this.activity = act;
        this.iniFile = ini;
        this.uiHandler = new Handler(Looper.getMainLooper());
        disableStrictModeIfPresent();
    }

    // Allows socket operations on Android 3.0+ without crashing on Android 1.6 (API 4)
    private static void disableStrictModeIfPresent() {
        try {
            Class<?> strictModeClass = Class.forName("android.os.StrictMode");
            Class<?> policyClass = Class.forName("android.os.StrictMode$ThreadPolicy");
            Class<?> builderClass = Class.forName("android.os.StrictMode$ThreadPolicy$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            Method permitAll = builderClass.getMethod("permitAll");
            builder = permitAll.invoke(builder);
            Object policy = builderClass.getMethod("build").invoke(builder);
            strictModeClass.getMethod("setThreadPolicy", policyClass).invoke(null, policy);
        } catch (Throwable ignored) {
            // Android 1.6 - 2.2: StrictMode does not exist
        }
    }

    // =========================================================================
    // STATIC PATH HELPERS EXPECTED BY AsyncerService.java
    // =========================================================================

    public static String normalizePath(String relPath) {
        if (relPath == null || relPath.length() == 0) return "";
        String p = relPath.replace('/', '\\').trim();
        while (p.startsWith("\\")) p = p.substring(1);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        return p;
    }

    // =========================================================================
    // CORE CONNECTION & FILE API EXPECTED BY AsyncerService.java
    // =========================================================================

    public synchronized boolean connect(ConnectionProfile prof) {
        disconnect();
        if (prof == null || prof.host == null || prof.host.trim().length() == 0) {
            return false;
        }
        this.currentProfile = prof;
        String host = prof.host.trim();
        int port = prof.port > 0 ? prof.port : 445;
        String user = prof.username != null ? prof.username.trim() : "";
        String pass = prof.password != null ? prof.password : "";
        String share = prof.share != null ? prof.share.trim() : "";

        try {
            boolean authOk = false;
            if (prof.protoPref != PROTO_SMB1) {
                try {
                    openSocket(host, port, prof.timeoutMs);
                    if (negotiateSmb2()) {
                        isSmb2 = true;
                        authenticateSmb2(user, pass);
                        authOk = true;
                    }
                } catch (Exception ignored) {
                    disconnect();
                }
            }

            if (!authOk && prof.protoPref != PROTO_SMB2) {
                openSocket(host, port, prof.timeoutMs);
                isSmb2 = false;
                if (!negotiateSmb1()) {
                    disconnect();
                    return false;
                }
                authenticateSmb1(user, pass);
                authOk = true;
            }

            if (!authOk) {
                disconnect();
                return false;
            }

            if (share.length() > 0) {
                treeConnect(host, share);
            }
            connected = true;
            return true;
        } catch (Exception e) {
            disconnect();
            return false;
        }
    }

    public synchronized boolean connect(String host, int port, String share, String user, String pass) {
        ConnectionProfile p = new ConnectionProfile();
        p.host = host;
        p.port = port > 0 ? port : 445;
        p.share = share != null ? share : "";
        p.username = user != null ? user : "";
        p.password = pass != null ? pass : "";
        p.protoPref = PROTO_AUTO;
        return connect(p);
    }

    public synchronized boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized String getConnectedHost() {
        return (currentProfile != null && currentProfile.host != null) ? currentProfile.host : "";
    }

    public synchronized String getConnectedShare() {
        return (currentProfile != null && currentProfile.share != null) ? currentProfile.share : "";
    }

    public synchronized void disconnect() {
        connected = false;
        smb2SessionId = 0;
        smb2TreeId = 0;
        smb1Uid = 0;
        smb1Tid = 0;
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        in = null;
        out = null;
        socket = null;
    }

    public synchronized void close() {
        disconnect();
    }

    private void openSocket(String host, int port, int timeoutMs) throws Exception {
        disconnect();
        int t = timeoutMs > 0 ? timeoutMs : 5000;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), t);
        socket.setSoTimeout(t);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    private void treeConnect(String host, String shareName) throws Exception {
        if (isSmb2) {
            String path = "\\\\" + host + "\\" + shareName;
            byte[] pathBytes = toUtf16LE(path);
            byte[] tcBody = new byte[8 + pathBytes.length];
            putU16LE(tcBody, 0, 9);
            putU16LE(tcBody, 4, 64 + 8);
            putU16LE(tcBody, 6, pathBytes.length);
            System.arraycopy(pathBytes, 0, tcBody, 8, pathBytes.length);
            sendNetBios(buildSmb2Packet(0x0003, tcBody));
            byte[] resp = recvNetBios();
            if (getU32LE(resp, 8) != 0) {
                throw new Exception("SMB2 TreeConnect failed for " + shareName);
            }
            smb2TreeId = getU32LE(resp, 36);
        } else {
            String path = "\\\\" + host.toUpperCase() + "\\" + shareName.toUpperCase() + "\u0000";
            byte[] pathBytes = path.getBytes("ASCII");
            byte[] srvType = "?????\u0000".getBytes("ASCII");
            int bcc = 1 + pathBytes.length + srvType.length;
            byte[] tcBody = new byte[1 + 8 + 2 + bcc];
            tcBody[0] = 4;
            tcBody[1] = (byte) 0xFF;
            putU16LE(tcBody, 7, 1);
            putU16LE(tcBody, 9, bcc);
            tcBody[11] = 0;
            System.arraycopy(pathBytes, 0, tcBody, 12, pathBytes.length);
            System.arraycopy(srvType, 0, tcBody, 12 + pathBytes.length, srvType.length);
            byte[] pkt = buildSmb1Packet((byte) 0x75, tcBody);
            pkt[11] = 0x01;
            sendNetBios(pkt);
            byte[] resp = recvNetBios();
            if (getU32LE(resp, 9) != 0) {
                throw new Exception("SMB1 TreeConnect failed for " + shareName);
            }
            smb1Tid = getU16LE(resp, 24);
        }
    }

    /**
     * Recursively or flatly fetches remote directory entries for AsyncerService.
     */
    public synchronized List<DirectoryItem> fetchRemoteDirContents(String remoteDirPath) {
        List<DirectoryItem> results = new ArrayList<DirectoryItem>();
        if (!isConnected()) return results;
        String baseRel = normalizePath(remoteDirPath);
        try {
            fetchDirRecursive(baseRel, "", results);
        } catch (Exception ignored) {
        }
        return results;
    }

    public synchronized List<DirectoryItem> listDirectory(String remoteDirPath) {
        List<DirectoryItem> results = new ArrayList<DirectoryItem>();
        if (!isConnected()) return results;
        try {
            if (isSmb2) {
                return listDirSingleSmb2(normalizePath(remoteDirPath), "");
            } else {
                return listDirSingleSmb1(normalizePath(remoteDirPath), "");
            }
        } catch (Exception ignored) {
            return results;
        }
    }

    private void fetchDirRecursive(String rootRel, String subRel, List<DirectoryItem> outList) throws Exception {
        String fullDir = rootRel;
        if (subRel.length() > 0) {
            fullDir = (rootRel.length() > 0) ? (rootRel + "\\" + subRel) : subRel;
        }
        List<DirectoryItem> levelItems = isSmb2
                ? listDirSingleSmb2(fullDir, subRel)
                : listDirSingleSmb1(fullDir, subRel);
        for (int i = 0; i < levelItems.size(); i++) {
            DirectoryItem item = levelItems.get(i);
            outList.add(item);
            if (item.isDirectory) {
                fetchDirRecursive(rootRel, item.path, outList);
            }
        }
    }

    private List<DirectoryItem> listDirSingleSmb2(String dirRelPath, String prefixRel) throws Exception {
        List<DirectoryItem> list = new ArrayList<DirectoryItem>();
        byte[] dirBytes = toUtf16LE(dirRelPath);
        byte[] crBody = new byte[56 + (dirBytes.length > 0 ? dirBytes.length : 2)];
        putU16LE(crBody, 0, 57);
        putU32LE(crBody, 24, 0x00100081); // FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES | SYNCHRONIZE
        putU32LE(crBody, 28, 0x00000010); // FILE_ATTRIBUTE_DIRECTORY
        putU32LE(crBody, 32, 0x00000007); // Share Read/Write/Delete
        putU32LE(crBody, 36, 0x00000001); // FILE_OPEN
        putU32LE(crBody, 40, 0x00000021); // FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT
        putU16LE(crBody, 44, 64 + 56);
        putU16LE(crBody, 46, dirBytes.length);
        if (dirBytes.length > 0) {
            System.arraycopy(dirBytes, 0, crBody, 56, dirBytes.length);
        }

        sendNetBios(buildSmb2Packet(0x0005, crBody));
        byte[] crResp = recvNetBios();
        if (getU32LE(crResp, 8) != 0) return list;

        byte[] fileId = new byte[16];
        System.arraycopy(crResp, 128, fileId, 0, 16);

        try {
            byte[] pattern = toUtf16LE("*");
            byte[] qdBody = new byte[32 + pattern.length];
            putU16LE(qdBody, 0, 33);
            qdBody[2] = 0x01; // FileDirectoryInformation (1)
            qdBody[3] = 0x01; // SMB2_RESTART_SCANS
            System.arraycopy(fileId, 0, qdBody, 8, 16);
            putU16LE(qdBody, 24, 64 + 32);
            putU16LE(qdBody, 26, pattern.length);
            putU32LE(qdBody, 28, 32768);
            System.arraycopy(pattern, 0, qdBody, 32, pattern.length);

            sendNetBios(buildSmb2Packet(0x000E, qdBody)); // SMB2_QUERY_DIRECTORY
            byte[] qdResp = recvNetBios();
            if (getU32LE(qdResp, 8) != 0) return list;

            int outOff = getU16LE(qdResp, 64 + 2);
            int outLen = getU32LE(qdResp, 64 + 4);
            int pos = outOff;
            int end = Math.min(qdResp.length, outOff + outLen);

            while (pos + 64 <= end) {
                int nextOff = getU32LE(qdResp, pos);
                long writeTime = getU64LE(qdResp, pos + 24);
                long endOfFile = getU64LE(qdResp, pos + 40);
                int attr = getU32LE(qdResp, pos + 56);
                int nameLen = getU32LE(qdResp, pos + 60);
                if (pos + 64 + nameLen <= end && nameLen > 0) {
                    String name = new String(qdResp, pos + 64, nameLen, "UTF-16LE");
                    if (!".".equals(name) && !"..".equals(name)) {
                        DirectoryItem item = new DirectoryItem();
                        item.name = name;
                        item.path = (prefixRel != null && prefixRel.length() > 0) ? (prefixRel + "\\" + name) : name;
                        item.isDirectory = (attr & 0x10) != 0;
                        item.isDir = item.isDirectory;
                        item.size = endOfFile;
                        item.attributes = attr;
                        item.lastModified = filetimeToMillis(writeTime);
                        list.add(item);
                    }
                }
                if (nextOff <= 0) break;
                pos += nextOff;
            }
        } finally {
            closeSmb2Handle(fileId);
        }
        return list;
    }

    private List<DirectoryItem> listDirSingleSmb1(String dirRelPath, String prefixRel) {
        return new ArrayList<DirectoryItem>();
    }

    public synchronized boolean makeDirectory(String remoteRelPath) {
        String clean = normalizePath(remoteRelPath);
        if (clean.length() == 0 || !isConnected()) return false;
        try {
            if (isSmb2) {
                byte[] nameBytes = toUtf16LE(clean);
                byte[] crBody = new byte[56 + nameBytes.length];
                putU16LE(crBody, 0, 57);
                putU32LE(crBody, 24, 0x00120116); // GENERIC_READ | GENERIC_WRITE
                putU32LE(crBody, 28, 0x00000010); // FILE_ATTRIBUTE_DIRECTORY
                putU32LE(crBody, 32, 0x00000007); // Share Read/Write/Delete
                putU32LE(crBody, 36, 0x00000002); // FILE_CREATE
                putU32LE(crBody, 40, 0x00000001); // FILE_DIRECTORY_FILE
                putU16LE(crBody, 44, 64 + 56);
                putU16LE(crBody, 46, nameBytes.length);
                System.arraycopy(nameBytes, 0, crBody, 56, nameBytes.length);
                sendNetBios(buildSmb2Packet(0x0005, crBody));
                byte[] resp = recvNetBios();
                int st = getU32LE(resp, 8);
                if (st == 0) {
                    byte[] fileId = new byte[16];
                    System.arraycopy(resp, 128, fileId, 0, 16);
                    closeSmb2Handle(fileId);
                    return true;
                }
                return st == 0xC0000035; // STATUS_OBJECT_NAME_COLLISION (already exists)
            } else {
                byte[] pathBytes = ("\u0004\\" + clean + "\u0000").getBytes("ASCII");
                byte[] body = new byte[3 + pathBytes.length];
                body[0] = 0;
                putU16LE(body, 1, pathBytes.length);
                System.arraycopy(pathBytes, 0, body, 3, pathBytes.length);
                byte[] pkt = buildSmb1Packet((byte) 0x00, body); // SMB_COM_CREATE_DIRECTORY
                pkt[11] = 0x01;
                sendNetBios(pkt);
                byte[] resp = recvNetBios();
                int st = getU32LE(resp, 9);
                return st == 0 || st == 0xC0000035;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean ensureParentDirs(String remoteRelFilePath) {
        String clean = normalizePath(remoteRelFilePath);
        int slash = clean.lastIndexOf('\\');
        if (slash <= 0) return true;
        String dirPart = clean.substring(0, slash);
        String[] parts = dirPart.split("\\\\");
        String cur = "";
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            cur = cur.length() == 0 ? parts[i] : (cur + "\\" + parts[i]);
            makeDirectory(cur);
        }
        return true;
    }

    public synchronized boolean uploadFile(File localFile, String remoteRelPath) {
        try {
            uploadFile(remoteRelPath, localFile);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void uploadFile(String remoteRelPath, File localFile) throws Exception {
        String cleanPath = normalizePath(remoteRelPath);
        ensureParentDirs(cleanPath);

        if (isSmb2) {
            byte[] nameBytes = toUtf16LE(cleanPath);
            byte[] crBody = new byte[56 + nameBytes.length];
            putU16LE(crBody, 0, 57);
            putU32LE(crBody, 24, 0x00120196);
            putU32LE(crBody, 28, 0x00000080);
            putU32LE(crBody, 32, 0x00000003);
            putU32LE(crBody, 36, 0x00000005); // FILE_OVERWRITE_IF
            putU32LE(crBody, 40, 0x00000040); // FILE_NON_DIRECTORY_FILE
            putU16LE(crBody, 44, 64 + 56);
            putU16LE(crBody, 46, nameBytes.length);
            System.arraycopy(nameBytes, 0, crBody, 56, nameBytes.length);

            sendNetBios(buildSmb2Packet(0x0005, crBody));
            byte[] crResp = recvNetBios();
            if (getU32LE(crResp, 8) != 0) {
                throw new Exception("SMB2 Create failed: 0x" + Integer.toHexString(getU32LE(crResp, 8)));
            }
            byte[] fileId = new byte[16];
            System.arraycopy(crResp, 128, fileId, 0, 16);

            FileInputStream fis = new FileInputStream(localFile);
            try {
                byte[] buf = new byte[32768];
                long offset = 0;
                int n;
                while ((n = fis.read(buf)) > 0) {
                    byte[] wrBody = new byte[48 + n];
                    putU16LE(wrBody, 0, 49);
                    putU16LE(wrBody, 2, 64 + 48);
                    putU32LE(wrBody, 4, n);
                    putU64LE(wrBody, 8, offset);
                    System.arraycopy(fileId, 0, wrBody, 16, 16);
                    System.arraycopy(buf, 0, wrBody, 48, n);
                    sendNetBios(buildSmb2Packet(0x0009, wrBody));
                    byte[] wrResp = recvNetBios();
                    if (getU32LE(wrResp, 8) != 0) {
                        throw new Exception("SMB2 Write failed at offset " + offset);
                    }
                    offset += n;
                }
            } finally {
                fis.close();
                closeSmb2Handle(fileId);
            }
        } else {
            byte[] pathBytes = ("\\" + cleanPath + "\u0000").getBytes("ASCII");
            byte[] crBody = new byte[1 + 48 + 2 + pathBytes.length];
            crBody[0] = 24;
            crBody[1] = (byte) 0xFF;
            putU16LE(crBody, 6, pathBytes.length - 1);
            putU32LE(crBody, 16, 0x00020196);
            putU32LE(crBody, 28, 0x00000080);
            putU32LE(crBody, 32, 0x00000003);
            putU32LE(crBody, 36, 0x00000005);
            putU32LE(crBody, 40, 0x00000040);
            putU16LE(crBody, 49, pathBytes.length);
            System.arraycopy(pathBytes, 0, crBody, 51, pathBytes.length);

            byte[] crPkt = buildSmb1Packet((byte) 0xA2, crBody);
            crPkt[11] = 0x01;
            sendNetBios(crPkt);
            byte[] crResp = recvNetBios();
            if (getU32LE(crResp, 9) != 0) {
                throw new Exception("SMB1 Create failed");
            }
            int fid = getU16LE(crResp, 32 + 1 + 5);

            FileInputStream fis = new FileInputStream(localFile);
            try {
                byte[] buf = new byte[16384];
                long offset = 0;
                int n;
                while ((n = fis.read(buf)) > 0) {
                    byte[] wrBody = new byte[1 + 28 + 2 + n];
                    wrBody[0] = 14;
                    wrBody[1] = (byte) 0xFF;
                    putU16LE(wrBody, 5, fid);
                    putU32LE(wrBody, 7, (int) (offset & 0xFFFFFFFFL));
                    putU16LE(wrBody, 21, n);
                    putU16LE(wrBody, 23, 32 + 1 + 28 + 2);
                    putU32LE(wrBody, 25, (int) (offset >>> 32));
                    putU16LE(wrBody, 29, n);
                    System.arraycopy(buf, 0, wrBody, 31, n);
                    sendNetBios(buildSmb1Packet((byte) 0x2F, wrBody));
                    byte[] wrResp = recvNetBios();
                    if (getU32LE(wrResp, 9) != 0) {
                        throw new Exception("SMB1 Write failed");
                    }
                    offset += n;
                }
            } finally {
                fis.close();
                closeSmb1Handle(fid);
            }
        }
    }

    public synchronized boolean downloadFile(String remoteRelPath, File localDest) {
        try {
            downloadFileChecked(remoteRelPath, localDest);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void downloadFileChecked(String remoteRelPath, File localDest) throws Exception {
        String cleanPath = normalizePath(remoteRelPath);
        File parent = localDest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        if (isSmb2) {
            byte[] nameBytes = toUtf16LE(cleanPath);
            byte[] crBody = new byte[56 + nameBytes.length];
            putU16LE(crBody, 0, 57);
            putU32LE(crBody, 24, 0x00120089);
            putU32LE(crBody, 32, 0x00000001);
            putU32LE(crBody, 36, 0x00000001);
            putU32LE(crBody, 40, 0x00000040);
            putU16LE(crBody, 44, 64 + 56);
            putU16LE(crBody, 46, nameBytes.length);
            System.arraycopy(nameBytes, 0, crBody, 56, nameBytes.length);

            sendNetBios(buildSmb2Packet(0x0005, crBody));
            byte[] crResp = recvNetBios();
            if (getU32LE(crResp, 8) != 0) {
                throw new Exception("SMB2 Open failed");
            }
            long fileSize = getU64LE(crResp, 64 + 48);
            byte[] fileId = new byte[16];
            System.arraycopy(crResp, 128, fileId, 0, 16);

            FileOutputStream fos = new FileOutputStream(localDest);
            try {
                long offset = 0;
                while (offset < fileSize) {
                    int toRead = (int) Math.min(32768L, fileSize - offset);
                    byte[] rdBody = new byte[49];
                    putU16LE(rdBody, 0, 49);
                    putU32LE(rdBody, 4, toRead);
                    putU64LE(rdBody, 8, offset);
                    System.arraycopy(fileId, 0, rdBody, 16, 16);
                    sendNetBios(buildSmb2Packet(0x0008, rdBody));
                    byte[] rdResp = recvNetBios();
                    int status = getU32LE(rdResp, 8);
                    if (status == 0xC0000011) break;
                    if (status != 0) throw new Exception("SMB2 Read failed");
                    int dOff = rdResp[64 + 2] & 0xFF;
                    int dLen = getU32LE(rdResp, 64 + 4);
                    if (dLen <= 0) break;
                    fos.write(rdResp, dOff, dLen);
                    offset += dLen;
                }
            } finally {
                fos.close();
                closeSmb2Handle(fileId);
            }
        } else {
            byte[] pathBytes = ("\\" + cleanPath + "\u0000").getBytes("ASCII");
            byte[] crBody = new byte[1 + 48 + 2 + pathBytes.length];
            crBody[0] = 24;
            crBody[1] = (byte) 0xFF;
            putU16LE(crBody, 6, pathBytes.length - 1);
            putU32LE(crBody, 16, 0x00120089);
            putU32LE(crBody, 28, 0x00000080);
            putU32LE(crBody, 32, 0x00000001);
            putU32LE(crBody, 36, 0x00000001);
            putU32LE(crBody, 40, 0x00000040);
            putU16LE(crBody, 49, pathBytes.length);
            System.arraycopy(pathBytes, 0, crBody, 51, pathBytes.length);

            byte[] crPkt = buildSmb1Packet((byte) 0xA2, crBody);
            crPkt[11] = 0x01;
            sendNetBios(crPkt);
            byte[] crResp = recvNetBios();
            if (getU32LE(crResp, 9) != 0) throw new Exception("SMB1 Open failed");
            int fid = getU16LE(crResp, 32 + 1 + 5);
            long fileSize = getU64LE(crResp, 32 + 1 + 47);

            FileOutputStream fos = new FileOutputStream(localDest);
            try {
                long offset = 0;
                while (offset < fileSize) {
                    int toRead = (int) Math.min(16384L, fileSize - offset);
                    byte[] rdBody = new byte[1 + 24 + 2];
                    rdBody[0] = 12;
                    rdBody[1] = (byte) 0xFF;
                    putU16LE(rdBody, 5, fid);
                    putU32LE(rdBody, 7, (int) (offset & 0xFFFFFFFFL));
                    putU16LE(rdBody, 11, toRead);
                    putU16LE(rdBody, 13, toRead);
                    putU32LE(rdBody, 21, (int) (offset >>> 32));
                    sendNetBios(buildSmb1Packet((byte) 0x2E, rdBody)); // SMB_COM_READ_ANDX
                    byte[] rdResp = recvNetBios();
                    if (getU32LE(rdResp, 9) != 0) break;
                    int dLen = getU16LE(rdResp, 32 + 1 + 11);
                    int dOff = getU16LE(rdResp, 32 + 1 + 13);
                    if (dLen <= 0 || dOff + dLen > rdResp.length) break;
                    fos.write(rdResp, dOff, dLen);
                    offset += dLen;
                }
            } finally {
                fos.close();
                closeSmb1Handle(fid);
            }
        }
    }

    public synchronized boolean deleteFile(String remoteRelPath) {
        String cleanPath = normalizePath(remoteRelPath);
        if (cleanPath.length() == 0 || !isConnected()) return false;
        try {
            if (isSmb2) {
                byte[] nameBytes = toUtf16LE(cleanPath);
                byte[] crBody = new byte[56 + nameBytes.length];
                putU16LE(crBody, 0, 57);
                putU32LE(crBody, 24, 0x00010000); // DELETE
                putU32LE(crBody, 32, 0x00000007);
                putU32LE(crBody, 36, 0x00000001); // FILE_OPEN
                putU32LE(crBody, 40, 0x00001000); // FILE_DELETE_ON_CLOSE
                putU16LE(crBody, 44, 64 + 56);
                putU16LE(crBody, 46, nameBytes.length);
                System.arraycopy(nameBytes, 0, crBody, 56, nameBytes.length);
                sendNetBios(buildSmb2Packet(0x0005, crBody));
                byte[] resp = recvNetBios();
                if (getU32LE(resp, 8) != 0) return false;
                byte[] fileId = new byte[16];
                System.arraycopy(resp, 128, fileId, 0, 16);
                closeSmb2Handle(fileId);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public synchronized boolean copySubtreeToLocal(String remoteRelDir, File localTargetDir) {
        List<DirectoryItem> items = fetchRemoteDirContents(remoteRelDir);
        for (int i = 0; i < items.size(); i++) {
            DirectoryItem item = items.get(i);
            File dest = new File(localTargetDir, item.path.replace('\\', File.separatorChar));
            if (item.isDirectory) {
                dest.mkdirs();
            } else {
                String fullRemote = normalizePath(remoteRelDir);
                fullRemote = fullRemote.length() > 0 ? (fullRemote + "\\" + item.path) : item.path;
                if (!downloadFile(fullRemote, dest)) return false;
            }
        }
        return true;
    }

    public synchronized boolean copyLocalSubtreeToRemote(File localSourceDir, String remoteRelDir) {
        if (localSourceDir == null || !localSourceDir.exists()) return false;
        File[] files = localSourceDir.listFiles();
        if (files == null) return true;
        String baseRemote = normalizePath(remoteRelDir);
        if (baseRemote.length() > 0) makeDirectory(baseRemote);
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String targetRel = baseRemote.length() > 0 ? (baseRemote + "\\" + f.getName()) : f.getName();
            if (f.isDirectory()) {
                makeDirectory(targetRel);
                if (!copyLocalSubtreeToRemote(f, targetRel)) return false;
            } else if (f.isFile()) {
                if (!uploadFile(f, targetRel)) return false;
            }
        }
        return true;
    }

    private void closeSmb2Handle(byte[] fileId) {
        try {
            byte[] clBody = new byte[24];
            putU16LE(clBody, 0, 24);
            System.arraycopy(fileId, 0, clBody, 8, 16);
            sendNetBios(buildSmb2Packet(0x0006, clBody));
            recvNetBios();
        } catch (Exception ignored) {}
    }

    private void closeSmb1Handle(int fid) {
        try {
            byte[] clBody = new byte[1 + 6 + 2];
            clBody[0] = 3;
            putU16LE(clBody, 1, fid);
            putU32LE(clBody, 3, 0xFFFFFFFF);
            sendNetBios(buildSmb1Packet((byte) 0x04, clBody));
            recvNetBios();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // SHARE ENUMERATION (SMB2 SRVSVC DCERPC + SMB1 LANMAN RAP)
    // =========================================================================

    public synchronized List<String> probeShares(String host, int port, String user, String pass) throws Exception {
        ConnectionProfile p = new ConnectionProfile();
        p.host = host;
        p.port = port > 0 ? port : 445;
        p.share = ""; // Connect without tree first
        p.username = user;
        p.password = pass;
        p.protoPref = PROTO_AUTO;
        if (!connect(p)) {
            throw new Exception("Could not connect/authenticate to " + host + ":" + p.port);
        }
        try {
            if (isSmb2) {
                return enumSharesSmb2(host);
            } else {
                return enumSharesSmb1(host);
            }
        } finally {
            disconnect();
        }
    }

    private List<String> enumSharesSmb2(String host) throws Exception {
        treeConnect(host, "IPC$");

        byte[] pipeName = toUtf16LE("srvsvc");
        byte[] crBody = new byte[56 + pipeName.length];
        putU16LE(crBody, 0, 57);
        putU32LE(crBody, 24, 0x0012019F);
        putU32LE(crBody, 32, 0x00000003);
        putU32LE(crBody, 36, 0x00000001);
        putU16LE(crBody, 44, 64 + 56);
        putU16LE(crBody, 46, pipeName.length);
        System.arraycopy(pipeName, 0, crBody, 56, pipeName.length);

        sendNetBios(buildSmb2Packet(0x0005, crBody));
        byte[] crResp = recvNetBios();
        if (getU32LE(crResp, 8) != 0) {
            throw new Exception("SMB2 srvsvc open failed");
        }
        byte[] fileId = new byte[16];
        System.arraycopy(crResp, 128, fileId, 0, 16);

        try {
            smb2WritePipe(fileId, buildDcerpcBindSrvsvc());
            smb2ReadPipe(fileId);

            smb2WritePipe(fileId, buildDcerpcNetShareEnum(host));
            byte[] rpcResp = smb2ReadPipe(fileId);
            return parseSrvsvcNetShareEnumLevel1(rpcResp);
        } finally {
            closeSmb2Handle(fileId);
        }
    }

    private void smb2WritePipe(byte[] fileId, byte[] data) throws Exception {
        byte[] wrBody = new byte[48 + data.length];
        putU16LE(wrBody, 0, 49);
        putU16LE(wrBody, 2, 64 + 48);
        putU32LE(wrBody, 4, data.length);
        System.arraycopy(fileId, 0, wrBody, 16, 16);
        System.arraycopy(data, 0, wrBody, 48, data.length);
        sendNetBios(buildSmb2Packet(0x0009, wrBody));
        byte[] resp = recvNetBios();
        if (getU32LE(resp, 8) != 0) {
            throw new Exception("SMB2 pipe write failed");
        }
    }

    private byte[] smb2ReadPipe(byte[] fileId) throws Exception {
        byte[] rdBody = new byte[49];
        putU16LE(rdBody, 0, 49);
        putU32LE(rdBody, 4, 16384);
        System.arraycopy(fileId, 0, rdBody, 16, 16);
        sendNetBios(buildSmb2Packet(0x0008, rdBody));
        byte[] resp = recvNetBios();
        if (getU32LE(resp, 8) != 0) {
            throw new Exception("SMB2 pipe read failed");
        }
        int dataOffset = resp[64 + 2] & 0xFF;
        int dataLen = getU32LE(resp, 64 + 4);
        if (dataOffset + dataLen > resp.length) dataLen = resp.length - dataOffset;
        byte[] outBuf = new byte[dataLen];
        System.arraycopy(resp, dataOffset, outBuf, 0, dataLen);
        return outBuf;
    }

    private List<String> enumSharesSmb1(String host) throws Exception {
        treeConnect(host, "IPC$");

        byte[] pipeName = "\\PIPE\\LANMAN\u0000".getBytes("ASCII");
        byte[] rapParams = new byte[] {
            0x00, 0x00,
            'W', 'r', 'L', 'e', 'h', 0x00,
            'B', '1', '3', 'B', 'W', 'z', 0x00,
            0x01, 0x00,
            0x00, 0x10
        };

        int paramOffset = 32 + 1 + 28 + 2 + pipeName.length;
        int dataOffset = paramOffset + rapParams.length;
        int bcc = pipeName.length + rapParams.length;

        byte[] body = new byte[1 + 28 + 2 + bcc];
        body[0] = 14;
        putU16LE(body, 1, rapParams.length);
        putU16LE(body, 3, 0);
        putU16LE(body, 5, 8);
        putU16LE(body, 7, 4096);
        putU16LE(body, 19, rapParams.length);
        putU16LE(body, 21, paramOffset);
        putU16LE(body, 23, 0);
        putU16LE(body, 25, dataOffset);
        putU16LE(body, 29, bcc);
        System.arraycopy(pipeName, 0, body, 31, pipeName.length);
        System.arraycopy(rapParams, 0, body, 31 + pipeName.length, rapParams.length);

        byte[] pkt = buildSmb1Packet((byte) 0x25, body);
        pkt[11] = 0x01;
        sendNetBios(pkt);
        byte[] resp = recvNetBios();
        List<String> list = new ArrayList<String>();
        if (getU32LE(resp, 9) != 0 || resp.length < 55) return list;

        int respParamOff = getU16LE(resp, 32 + 9);
        int respDataOff = getU16LE(resp, 32 + 15);
        if (respParamOff + 6 > resp.length || respDataOff >= resp.length) return list;

        int entriesRead = getU16LE(resp, respParamOff + 4);
        int pos = respDataOff;
        for (int i = 0; i < entriesRead && pos + 20 <= resp.length; i++) {
            int end = pos;
            while (end < pos + 13 && resp[end] != 0) end++;
            String shName = new String(resp, pos, end - pos, "ASCII").trim();
            if (shName.length() > 0 && !"IPC$".equalsIgnoreCase(shName)) {
                list.add(shName);
            }
            pos += 20;
        }
        return list;
    }

    // =========================================================================
    // UI PANEL BUILDER (Share Dropdown + INI Persistence + Test Button + LuaJ)
    // =========================================================================

    public View buildSmbPanel(Activity act, File ini) {
        this.activity = act;
        this.iniFile = ini;
        if (this.uiHandler == null) {
            this.uiHandler = new Handler(Looper.getMainLooper());
        }
        return buildSmbPanel();
    }

    public View buildSmbPanel() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        etHost = addLabeledInput(root, "SMB Server IP / Host:", "192.168.1.100", false);
        etPort = addLabeledInput(root, "SMB Port:", "445", false);
        etUser = addLabeledInput(root, "Username (blank for guest):", "guest", false);
        etPass = addLabeledInput(root, "Password:", "", true);

        TextView lblShare = new TextView(activity);
        lblShare.setText("SMB Share:");
        root.addView(lblShare);

        LinearLayout shareRow = new LinearLayout(activity);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);

        spShares = new Spinner(activity);
        shareAdapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, shareList);
        shareAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spShares.setAdapter(shareAdapter);

        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        shareRow.addView(spShares, spLp);

        btnTestShares = new Button(activity);
        btnTestShares.setText("Test / Probe Shares");
        btnTestShares.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                probeAndSaveSharesAsync();
            }
        });
        shareRow.addView(btnTestShares);
        root.addView(shareRow);

        TextView lblCmd = new TextView(activity);
        lblCmd.setText("Run External Command / LuaJ Script (.lua):");
        root.addView(lblCmd);

        LinearLayout cmdRow = new LinearLayout(activity);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);

        etExternalCmd = new EditText(activity);
        etExternalCmd.setSingleLine(true);
        etExternalCmd.setHint("/sdcard/script.lua or shell cmd");
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        cmdRow.addView(etExternalCmd, cmdLp);

        btnBrowseLua = new Button(activity);
        btnBrowseLua.setText("Browse...");
        btnBrowseLua.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showLuaFileBrowser(getDefaultStartDir());
            }
        });
        cmdRow.addView(btnBrowseLua);

        btnRunCmd = new Button(activity);
        btnRunCmd.setText("Run");
        btnRunCmd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveIni();
                executeCommandOrLua(etExternalCmd.getText().toString().trim());
            }
        });
        cmdRow.addView(btnRunCmd);
        root.addView(cmdRow);

        tvStatus = new TextView(activity);
        tvStatus.setText("Ready.");
        tvStatus.setPadding(0, 8, 0, 0);
        root.addView(tvStatus);

        loadIni();
        return root;
    }

    private EditText addLabeledInput(LinearLayout parent, String label, String defVal, boolean isPassword) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        parent.addView(tv);

        EditText et = new EditText(activity);
        et.setSingleLine(true);
        et.setText(defVal);
        if (isPassword) {
            et.setInputType(0x00000081);
        }
        parent.addView(et);
        return et;
    }

    public void loadIni() {
        shareList.clear();
        String selectedShare = "";
        if (iniFile != null && iniFile.exists()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(iniFile));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();

                    if ("host".equalsIgnoreCase(k) && etHost != null) etHost.setText(v);
                    else if ("port".equalsIgnoreCase(k) && etPort != null) etPort.setText(v);
                    else if ("user".equalsIgnoreCase(k) && etUser != null) etUser.setText(v);
                    else if ("pass".equalsIgnoreCase(k) && etPass != null) etPass.setText(v);
                    else if ("share".equalsIgnoreCase(k)) selectedShare = v;
                    else if ("shares".equalsIgnoreCase(k)) {
                        String[] parts = splitPipe(v);
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i].length() > 0 && !shareList.contains(parts[i])) {
                                shareList.add(parts[i]);
                            }
                        }
                    } else if ("ext_cmd".equalsIgnoreCase(k) && etExternalCmd != null) {
                        etExternalCmd.setText(v);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
        }

        if (shareList.isEmpty()) {
            shareList.add("PUBLIC");
        }
        if (selectedShare.length() > 0 && !shareList.contains(selectedShare)) {
            shareList.add(0, selectedShare);
        }
        if (shareAdapter != null) {
            shareAdapter.notifyDataSetChanged();
        }
        if (spShares != null && selectedShare.length() > 0) {
            int idx = shareList.indexOf(selectedShare);
            if (idx >= 0) spShares.setSelection(idx);
        }
    }

    public void saveIni() {
        if (iniFile == null) return;
        FileOutputStream fos = null;
        try {
            String selShare = getSelectedShare();
            if (selShare.length() > 0 && !shareList.contains(selShare)) {
                shareList.add(selShare);
            }
            StringBuilder sbShares = new StringBuilder();
            for (int i = 0; i < shareList.size(); i++) {
                if (i > 0) sbShares.append('|');
                sbShares.append(shareList.get(i));
            }

            StringBuilder outStr = new StringBuilder();
            outStr.append("[SMB]\r\n");
            if (etHost != null) outStr.append("host=").append(etHost.getText().toString().trim()).append("\r\n");
            if (etPort != null) outStr.append("port=").append(etPort.getText().toString().trim()).append("\r\n");
            if (etUser != null) outStr.append("user=").append(etUser.getText().toString().trim()).append("\r\n");
            if (etPass != null) outStr.append("pass=").append(etPass.getText().toString()).append("\r\n");
            outStr.append("share=").append(selShare).append("\r\n");
            outStr.append("shares=").append(sbShares.toString()).append("\r\n");
            if (etExternalCmd != null) outStr.append("ext_cmd=").append(etExternalCmd.getText().toString().trim()).append("\r\n");

            fos = new FileOutputStream(iniFile, false);
            fos.write(outStr.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    public String getSelectedShare() {
        if (spShares == null) return "";
        Object item = spShares.getSelectedItem();
        return item != null ? item.toString().trim() : "";
    }

    private void probeAndSaveSharesAsync() {
        final String host = etHost.getText().toString().trim();
        final int port = parseIntSafe(etPort.getText().toString().trim(), 445);
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();

        if (host.length() == 0) {
            setStatus("Error: Host is empty.");
            return;
        }

        btnTestShares.setEnabled(false);
        setStatus("Probing SMB server " + host + ":" + port + " ...");

        new Thread(new Runnable() {
            public void run() {
                final SmbClient probeClient = new SmbClient();
                try {
                    final List<String> discovered = probeClient.probeShares(host, port, user, pass);
                    uiHandler.post(new Runnable() {
                        public void run() {
                            btnTestShares.setEnabled(true);
                            String currentSelected = getSelectedShare();
                            for (int i = 0; i < discovered.size(); i++) {
                                String sh = discovered.get(i);
                                if (!shareList.contains(sh)) {
                                    shareList.add(sh);
                                }
                            }
                            if (discovered.size() > 0 && shareList.size() > 1) {
                                shareList.remove("PUBLIC");
                            }
                            shareAdapter.notifyDataSetChanged();
                            if (discovered.size() > 0) {
                                int idx = shareList.indexOf(discovered.get(0));
                                if (currentSelected.length() > 0 && shareList.contains(currentSelected)) {
                                    idx = shareList.indexOf(currentSelected);
                                }
                                if (idx >= 0) spShares.setSelection(idx);
                            }
                            saveIni();
                            setStatus("Connected (" + (probeClient.isSmb2 ? "SMB2" : "SMB1") + "). Found "
                                    + discovered.size() + " share(s). Saved to INI.");
                            Toast.makeText(activity, "Found " + discovered.size() + " share(s)", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        public void run() {
                            btnTestShares.setEnabled(true);
                            setStatus("SMB Test Failed: " + e.getMessage());
                            Toast.makeText(activity, "SMB Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    probeClient.disconnect();
                }
            }
        }).start();
    }

    private void setStatus(final String msg) {
        if (tvStatus == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvStatus.setText(msg);
        } else if (uiHandler != null) {
            uiHandler.post(new Runnable() {
                public void run() {
                    tvStatus.setText(msg);
                }
            });
        }
    }

    // =========================================================================
    // BROWSE & EXECUTE LUAJ SCRIPT / EXTERNAL COMMAND
    // =========================================================================

    private File getDefaultStartDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists() && ext.canRead()) return ext;
        } catch (Throwable ignored) {}
        return new File("/");
    }

    public void showLuaFileBrowser(final File dir) {
        if (activity == null) return;
        File[] files = null;
        try {
            files = dir.listFiles();
        } catch (Throwable ignored) {}

        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        if (dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add(".. (Parent Directory)");
        }

        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    entries.add(files[i]);
                    labels.add("[" + files[i].getName() + "]");
                }
            }
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isDirectory()) {
                    String name = files[i].getName();
                    if (name.toLowerCase().endsWith(".lua") || name.toLowerCase().endsWith(".sh")) {
                        entries.add(files[i]);
                        labels.add(name);
                    }
                }
            }
        }

        labels.add("Use System File Picker...");

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(dir.getAbsolutePath());
        b.setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == labels.size() - 1) {
                    launchSystemPicker();
                    return;
                }
                File target = entries.get(which);
                if (target.isDirectory()) {
                    showLuaFileBrowser(target);
                } else {
                    if (etExternalCmd != null) {
                        etExternalCmd.setText(target.getAbsolutePath());
                    }
                    saveIni();
                }
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void launchSystemPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(Intent.createChooser(intent, "Select .lua Script"), REQ_PICK_LUA);
        } catch (Exception e) {
            Toast.makeText(activity, "No system file picker available.", Toast.LENGTH_SHORT).show();
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_LUA && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null && etExternalCmd != null) {
                String path = uri.getPath();
                etExternalCmd.setText(path != null ? path : uri.toString());
                saveIni();
            }
        }
    }

    public void executeCommandOrLua(final String cmdOrPath) {
        if (cmdOrPath == null || cmdOrPath.length() == 0) {
            setStatus("No script or command specified.");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (cmdOrPath.toLowerCase().endsWith(".lua")) {
                        setStatus("Running LuaJ script: " + cmdOrPath);
                        String res = runLuaJScript(cmdOrPath);
                        setStatus("LuaJ finished: " + res);
                    } else {
                        setStatus("Running shell command: " + cmdOrPath);
                        Process proc = Runtime.getRuntime().exec(cmdOrPath);
                        InputStream is = proc.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[512];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            baos.write(buf, 0, n);
                        }
                        int code = proc.waitFor();
                        String outStr = new String(baos.toByteArray(), "UTF-8").trim();
                        setStatus("Exit " + code + (outStr.length() > 0 ? ": " + outStr : ""));
                    }
                } catch (Exception e) {
                    setStatus("Execution error: " + e.getMessage());
                }
            }
        }).start();
    }

    public String runLuaJScript(String scriptPath) throws Exception {
        Class<?> jsePlatform = Class.forName("org.luaj.vm2.lib.jse.JsePlatform");
        Method standardGlobals = jsePlatform.getMethod("standardGlobals");
        Object globals = standardGlobals.invoke(null);

        Class<?> luaValueClass = Class.forName("org.luaj.vm2.LuaValue");
        Method setMethod = luaValueClass.getMethod("set", String.class, String.class);
        setMethod.invoke(globals, "SMB_HOST", etHost != null ? etHost.getText().toString().trim() : getConnectedHost());
        setMethod.invoke(globals, "SMB_SHARE", spShares != null ? getSelectedShare() : getConnectedShare());
        setMethod.invoke(globals, "SMB_USER", etUser != null ? etUser.getText().toString().trim() : "");

        Method loadFile = globals.getClass().getMethod("loadfile", String.class);
        Object chunk = loadFile.invoke(globals, scriptPath);
        Method call = luaValueClass.getMethod("call");
        Object result = call.invoke(chunk);
        return result != null ? result.toString() : "OK";
    }

    // =========================================================================
    // LOW-LEVEL SMB2 / SMB1 / NTLMSSP / DCERPC PROTOCOL HELPERS
    // =========================================================================

    private boolean negotiateSmb2() throws Exception {
        smb2MsgId = 0;
        byte[] body = new byte[36 + 4];
        putU16LE(body, 0, 36);
        putU16LE(body, 2, 2);
        putU16LE(body, 4, 1);
        putU16LE(body, 36, 0x0202);
        putU16LE(body, 38, 0x0210);

        sendNetBios(buildSmb2Packet(0x0000, body));
        byte[] resp = recvNetBios();
        if (resp.length < 64 || resp[0] != (byte) 0xFE || resp[1] != 'S') return false;
        return getU32LE(resp, 8) == 0;
    }

    private void authenticateSmb2(String user, String pass) throws Exception {
        byte[] ntlmNeg = buildNtlmsspNegotiate();
        byte[] setup1 = wrapSmb2SessionSetup(ntlmNeg);
        sendNetBios(buildSmb2Packet(0x0001, setup1));
        byte[] resp1 = recvNetBios();

        int status1 = getU32LE(resp1, 8);
        smb2SessionId = getU64LE(resp1, 40);
        if (status1 == 0) return;
        if (status1 != 0xC0000016) {
            throw new Exception("SMB2 SessionSetup Step 1 status: 0x" + Integer.toHexString(status1));
        }

        byte[] challengeBlob = extractNtlmsspFromPacket(resp1);
        byte[] ntlmAuth = buildNtlmsspAuth(challengeBlob, user, pass);

        byte[] setup2 = wrapSmb2SessionSetup(ntlmAuth);
        sendNetBios(buildSmb2Packet(0x0001, setup2));
        byte[] resp2 = recvNetBios();
        int status2 = getU32LE(resp2, 8);
        if (status2 != 0) {
            throw new Exception("SMB2 Auth failed (0x" + Integer.toHexString(status2) + ")");
        }
    }

    private byte[] wrapSmb2SessionSetup(byte[] secBlob) {
        byte[] body = new byte[24 + secBlob.length];
        putU16LE(body, 0, 25);
        body[2] = 0;
        body[3] = 1;
        putU16LE(body, 12, 64 + 24);
        putU16LE(body, 14, secBlob.length);
        System.arraycopy(secBlob, 0, body, 24, secBlob.length);
        return body;
    }

    private byte[] buildSmb2Packet(int command, byte[] body) {
        byte[] pkt = new byte[64 + body.length];
        pkt[0] = (byte) 0xFE;
        pkt[1] = 'S';
        pkt[2] = 'M';
        pkt[3] = 'B';
        putU16LE(pkt, 4, 64);
        putU16LE(pkt, 12, command);
        putU16LE(pkt, 14, 31);
        putU64LE(pkt, 24, smb2MsgId++);
        putU32LE(pkt, 36, smb2TreeId);
        putU64LE(pkt, 40, smb2SessionId);
        System.arraycopy(body, 0, pkt, 64, body.length);
        return pkt;
    }

    private boolean negotiateSmb1() throws Exception {
        byte[] dialect = "\u0002NT LM 0.12\u0000".getBytes("ASCII");
        byte[] body = new byte[3 + dialect.length];
        body[0] = 0;
        putU16LE(body, 1, dialect.length);
        System.arraycopy(dialect, 0, body, 3, dialect.length);

        sendNetBios(buildSmb1Packet((byte) 0x72, body));
        byte[] resp = recvNetBios();
        return resp.length >= 32 && resp[0] == (byte) 0xFF && resp[1] == 'S' && getU32LE(resp, 9) == 0;
    }

    private void authenticateSmb1(String user, String pass) throws Exception {
        byte[] ntlmNeg = buildNtlmsspNegotiate();
        sendNetBios(buildSmb1SessionSetupExtended(ntlmNeg));
        byte[] resp1 = recvNetBios();
        int status1 = getU32LE(resp1, 9);
        smb1Uid = getU16LE(resp1, 28);
        if (status1 == 0) return;

        if (status1 == 0xC0000016) {
            byte[] challenge = extractNtlmsspFromPacket(resp1);
            byte[] ntlmAuth = buildNtlmsspAuth(challenge, user, pass);
            sendNetBios(buildSmb1SessionSetupExtended(ntlmAuth));
            byte[] resp2 = recvNetBios();
            int status2 = getU32LE(resp2, 9);
            smb1Uid = getU16LE(resp2, 28);
            if (status2 == 0) return;
            throw new Exception("SMB1 Auth failed (0x" + Integer.toHexString(status2) + ")");
        }
        throw new Exception("SMB1 SessionSetup failed (0x" + Integer.toHexString(status1) + ")");
    }

    private byte[] buildSmb1SessionSetupExtended(byte[] secBlob) throws Exception {
        byte[] nativeOs = "Android\u0000Asyncer\u0000".getBytes("ASCII");
        int bcc = secBlob.length + nativeOs.length;
        byte[] body = new byte[1 + 24 + 2 + bcc];
        body[0] = 12;
        body[1] = (byte) 0xFF;
        putU16LE(body, 5, 16644);
        putU16LE(body, 7, 2);
        putU16LE(body, 9, 1);
        putU16LE(body, 15, secBlob.length);
        putU32LE(body, 21, 0x800000D4);
        putU16LE(body, 25, bcc);
        System.arraycopy(secBlob, 0, body, 27, secBlob.length);
        System.arraycopy(nativeOs, 0, body, 27 + secBlob.length, nativeOs.length);
        return buildSmb1Packet((byte) 0x73, body);
    }

    private byte[] buildSmb1Packet(byte command, byte[] body) {
        byte[] pkt = new byte[32 + body.length];
        pkt[0] = (byte) 0xFF;
        pkt[1] = 'S';
        pkt[2] = 'M';
        pkt[3] = 'B';
        pkt[4] = command;
        pkt[9] = 0x18;
        putU16LE(pkt, 10, 0xC803);
        putU16LE(pkt, 24, smb1Tid);
        putU16LE(pkt, 26, 0x1234);
        putU16LE(pkt, 28, smb1Uid);
        putU16LE(pkt, 30, smb1Mid++);
        System.arraycopy(body, 0, pkt, 32, body.length);
        return pkt;
    }

    private byte[] buildDcerpcBindSrvsvc() {
        return new byte[] {
            0x05, 0x00, 0x0B, 0x03, 0x10, 0x00, 0x00, 0x00,
            0x48, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            (byte) 0xB8, 0x10, (byte) 0xB8, 0x10, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            (byte) 0xC8, 0x4F, 0x32, 0x4B, 0x70, 0x16, (byte) 0xD3, 0x01,
            0x12, 0x78, 0x5A, 0x47, (byte) 0xBF, 0x6E, (byte) 0xE1, (byte) 0x88,
            0x03, 0x00, 0x00, 0x00,
            0x04, 0x5D, (byte) 0x88, (byte) 0x8A, (byte) 0xEB, 0x1C, (byte) 0xC9, 0x11,
            (byte) 0x9F, (byte) 0xE8, 0x08, 0x00, 0x2B, 0x10, 0x48, 0x60,
            0x02, 0x00, 0x00, 0x00
        };
    }

    private byte[] buildDcerpcNetShareEnum(String serverHost) throws Exception {
        String unc = "\\\\" + serverHost + "\u0000";
        byte[] uncUtf16 = toUtf16LE(unc);
        int charCount = unc.length();
        int pad = (4 - (uncUtf16.length & 3)) & 3;

        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        writeU32LE(stub, 0x00020000);
        writeU32LE(stub, charCount);
        writeU32LE(stub, 0);
        writeU32LE(stub, charCount);
        stub.write(uncUtf16);
        for (int i = 0; i < pad; i++) stub.write(0);

        writeU32LE(stub, 1);
        writeU32LE(stub, 1);
        writeU32LE(stub, 0x00020004);
        writeU32LE(stub, 0);
        writeU32LE(stub, 0);
        writeU32LE(stub, 0xFFFFFFFF);
        writeU32LE(stub, 0);

        byte[] stubBytes = stub.toByteArray();
        byte[] pdu = new byte[24 + stubBytes.length];
        pdu[0] = 5;
        pdu[1] = 0;
        pdu[2] = 0;
        pdu[3] = 3;
        pdu[4] = 0x10;
        putU16LE(pdu, 8, pdu.length);
        putU32LE(pdu, 12, 2);
        putU32LE(pdu, 16, stubBytes.length);
        putU16LE(pdu, 22, 15);
        System.arraycopy(stubBytes, 0, pdu, 24, stubBytes.length);
        return pdu;
    }

    private List<String> parseSrvsvcNetShareEnumLevel1(byte[] pdu) {
        List<String> result = new ArrayList<String>();
        if (pdu == null || pdu.length < 48) return result;
        int entriesRead = getU32LE(pdu, 36);
        int maxCount = getU32LE(pdu, 44);
        int count = (entriesRead > 0 && entriesRead < 512) ? entriesRead : maxCount;
        if (count <= 0 || count > 512) return result;

        int pos = 48;
        int[] namePtrs = new int[count];
        int[] commentPtrs = new int[count];
        for (int i = 0; i < count; i++) {
            if (pos + 12 > pdu.length) return result;
            namePtrs[i] = getU32LE(pdu, pos);
            commentPtrs[i] = getU32LE(pdu, pos + 8);
            pos += 12;
        }

        for (int i = 0; i < count; i++) {
            if (namePtrs[i] != 0) {
                String shareName = readNdrUnicodeString(pdu, pos);
                pos = advanceNdrUnicodeString(pdu, pos);
                if (shareName != null && shareName.length() > 0 && !"IPC$".equalsIgnoreCase(shareName)) {
                    result.add(shareName);
                }
            }
            if (commentPtrs[i] != 0) {
                pos = advanceNdrUnicodeString(pdu, pos);
            }
        }
        return result;
    }

    private String readNdrUnicodeString(byte[] buf, int pos) {
        pos = (pos + 3) & ~3;
        if (pos + 12 > buf.length) return null;
        int actualCount = getU32LE(buf, pos + 8);
        pos += 12;
        if (actualCount <= 0 || pos + actualCount * 2 > buf.length) return null;
        int byteLen = actualCount * 2;
        if (byteLen >= 2 && buf[pos + byteLen - 2] == 0 && buf[pos + byteLen - 1] == 0) {
            byteLen -= 2;
        }
        try {
            return new String(buf, pos, byteLen, "UTF-16LE").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private int advanceNdrUnicodeString(byte[] buf, int pos) {
        pos = (pos + 3) & ~3;
        if (pos + 12 > buf.length) return buf.length;
        int actualCount = getU32LE(buf, pos + 8);
        pos += 12 + (actualCount * 2);
        return (pos + 3) & ~3;
    }

    private byte[] buildNtlmsspNegotiate() {
        return new byte[] {
            'N', 'T', 'L', 'M', 'S', 'S', 'P', 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x07, (byte) 0x82, 0x08, (byte) 0xA2,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
    }

    private byte[] extractNtlmsspFromPacket(byte[] pkt) throws Exception {
        for (int i = 0; i <= pkt.length - 8; i++) {
            if (pkt[i] == 'N' && pkt[i + 1] == 'T' && pkt[i + 2] == 'L' && pkt[i + 3] == 'M'
                    && pkt[i + 4] == 'S' && pkt[i + 5] == 'S' && pkt[i + 6] == 'P' && pkt[i + 7] == 0) {
                byte[] blob = new byte[pkt.length - i];
                System.arraycopy(pkt, i, blob, 0, blob.length);
                return blob;
            }
        }
        throw new Exception("Server did not return an NTLMSSP challenge");
    }

    private byte[] buildNtlmsspAuth(byte[] type2, String username, String password) throws Exception {
        if (username == null || username.length() == 0 || "guest".equalsIgnoreCase(username)) {
            byte[] anon = new byte[64];
            System.arraycopy("NTLMSSP\u0000".getBytes("ASCII"), 0, anon, 0, 8);
            putU32LE(anon, 8, 3);
            putU32LE(anon, 60, 0xA2088205);
            return anon;
        }

        byte[] serverChallenge = new byte[8];
        System.arraycopy(type2, 24, serverChallenge, 0, 8);

        byte[] targetInfo = new byte[0];
        if (type2.length >= 48) {
            int tiLen = getU16LE(type2, 40);
            int tiOff = getU32LE(type2, 44);
            if (tiLen > 0 && tiOff + tiLen <= type2.length) {
                targetInfo = new byte[tiLen];
                System.arraycopy(type2, tiOff, targetInfo, 0, tiLen);
            }
        }

        byte[] ntProofAndBlob = computeNtlmv2Response(username, "", password, serverChallenge, targetInfo);
        byte[] userBytes = toUtf16LE(username);
        byte[] hostBytes = toUtf16LE("ANDROID");

        int offset = 64;
        int ntOff = offset;
        offset += ntProofAndBlob.length;
        int userOff = offset;
        offset += userBytes.length;
        int hostOff = offset;
        offset += hostBytes.length;

        byte[] type3 = new byte[offset];
        System.arraycopy("NTLMSSP\u0000".getBytes("ASCII"), 0, type3, 0, 8);
        putU32LE(type3, 8, 3);

        putU16LE(type3, 20, ntProofAndBlob.length);
        putU16LE(type3, 22, ntProofAndBlob.length);
        putU32LE(type3, 24, ntOff);

        putU16LE(type3, 36, userBytes.length);
        putU16LE(type3, 38, userBytes.length);
        putU32LE(type3, 40, userOff);

        putU16LE(type3, 44, hostBytes.length);
        putU16LE(type3, 46, hostBytes.length);
        putU32LE(type3, 48, hostOff);

        putU32LE(type3, 60, 0xA2888205);

        System.arraycopy(ntProofAndBlob, 0, type3, ntOff, ntProofAndBlob.length);
        System.arraycopy(userBytes, 0, type3, userOff, userBytes.length);
        System.arraycopy(hostBytes, 0, type3, hostOff, hostBytes.length);
        return type3;
    }

    private byte[] computeNtlmv2Response(String user, String domain, String pass,
                                         byte[] serverChallenge, byte[] targetInfo) throws Exception {
        byte[] ntHash = md4(toUtf16LE(pass));
        byte[] userDom = toUtf16LE(user.toUpperCase() + domain);
        byte[] ntlmv2Key = hmacMd5(ntHash, userDom);

        byte[] clientChallenge = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        long winTime = (System.currentTimeMillis() + 11644473600000L) * 10000L;

        byte[] blob = new byte[28 + targetInfo.length + 4];
        blob[0] = 1;
        blob[1] = 1;
        putU64LE(blob, 8, winTime);
        System.arraycopy(clientChallenge, 0, blob, 16, 8);
        System.arraycopy(targetInfo, 0, blob, 28, targetInfo.length);

        byte[] chAndBlob = new byte[8 + blob.length];
        System.arraycopy(serverChallenge, 0, chAndBlob, 0, 8);
        System.arraycopy(blob, 0, chAndBlob, 8, blob.length);

        byte[] ntProofStr = hmacMd5(ntlmv2Key, chAndBlob);
        byte[] fullResp = new byte[16 + blob.length];
        System.arraycopy(ntProofStr, 0, fullResp, 0, 16);
        System.arraycopy(blob, 0, fullResp, 16, blob.length);
        return fullResp;
    }

    private static byte[] hmacMd5(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacMD5");
        mac.init(new SecretKeySpec(key, "HmacMD5"));
        return mac.doFinal(data);
    }

    private static byte[] md4(byte[] input) {
        int origLen = input.length;
        int tailLen = origLen % 64;
        int padLen = (tailLen < 56) ? (56 - tailLen) : (120 - tailLen);
        byte[] msg = new byte[origLen + padLen + 8];
        System.arraycopy(input, 0, msg, 0, origLen);
        msg[origLen] = (byte) 0x80;
        long bitLen = (long) origLen * 8L;
        for (int i = 0; i < 8; i++) {
            msg[origLen + padLen + i] = (byte) ((bitLen >>> (8 * i)) & 0xFF);
        }

        int A = 0x67452301, B = 0xefcdab89, C = 0x98badcfe, D = 0x10325476;
        int[] X = new int[16];

        for (int off = 0; off < msg.length; off += 64) {
            for (int i = 0; i < 16; i++) {
                X[i] = getU32LE(msg, off + i * 4);
            }
            int AA = A, BB = B, CC = C, DD = D;

            int[] r1 = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
            int[] s1 = {3,7,11,19};
            for (int i = 0; i < 16; i++) {
                int f = (A & B) | (~A & C);
                int tmp = rotl(A + f + X[r1[i]], s1[i % 4]);
                A = D; D = C; C = B; B = tmp;
            }

            int[] r2 = {0,4,8,12,1,5,9,13,2,6,10,14,3,7,11,15};
            int[] s2 = {3,5,9,13};
            for (int i = 0; i < 16; i++) {
                int g = (A & B) | (A & C) | (B & C);
                int tmp = rotl(A + g + X[r2[i]] + 0x5A827999, s2[i % 4]);
                A = D; D = C; C = B; B = tmp;
            }

            int[] r3 = {0,8,4,12,2,10,6,14,1,9,5,13,3,11,7,15};
            int[] s3 = {3,9,11,15};
            for (int i = 0; i < 16; i++) {
                int h = A ^ B ^ C;
                int tmp = rotl(A + h + X[r3[i]] + 0x6ED9EBA1, s3[i % 4]);
                A = D; D = C; C = B; B = tmp;
            }

            A += AA; B += BB; C += CC; D += DD;
        }

        byte[] out = new byte[16];
        putU32LE(out, 0, A);
        putU32LE(out, 4, B);
        putU32LE(out, 8, C);
        putU32LE(out, 12, D);
        return out;
    }

    private static int rotl(int val, int bits) {
        return (val << bits) | (val >>> (32 - bits));
    }

    private void sendNetBios(byte[] payload) throws Exception {
        int len = payload.length;
        byte[] frame = new byte[4 + len];
        frame[0] = 0;
        frame[1] = (byte) ((len >> 16) & 0xFF);
        frame[2] = (byte) ((len >> 8) & 0xFF);
        frame[3] = (byte) (len & 0xFF);
        System.arraycopy(payload, 0, frame, 4, len);
        out.write(frame);
        out.flush();
    }

    private byte[] recvNetBios() throws Exception {
        byte[] hdr = new byte[4];
        in.readFully(hdr);
        int len = ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
        if (len <= 0 || len > 524288) {
            throw new Exception("Invalid NetBIOS frame length: " + len);
        }
        byte[] body = new byte[len];
        in.readFully(body);
        return body;
    }

    private static long filetimeToMillis(long ft) {
        if (ft <= 0) return 0L;
        return (ft / 10000L) - 11644473600000L;
    }

    private static String[] splitPipe(String s) {
        List<String> list = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '|') {
                list.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start <= s.length()) {
            list.add(s.substring(start).trim());
        }
        return list.toArray(new String[list.size()]);
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static byte[] toUtf16LE(String s) throws Exception {
        return s.getBytes("UTF-16LE");
    }

    private static int getU16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int getU32LE(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static long getU64LE(byte[] b, int off) {
        return ((long) getU32LE(b, off) & 0xFFFFFFFFL)
                | (((long) getU32LE(b, off + 4) & 0xFFFFFFFFL) << 32);
    }

    private static void putU16LE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    private static void putU32LE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    private static void putU64LE(byte[] b, int off, long v) {
        putU32LE(b, off, (int) (v & 0xFFFFFFFFL));
        putU32LE(b, off + 4, (int) ((v >>> 32) & 0xFFFFFFFFL));
    }

    private static void writeU32LE(ByteArrayOutputStream baos, int v) {
        baos.write(v & 0xFF);
        baos.write((v >>> 8) & 0xFF);
        baos.write((v >>> 16) & 0xFF);
        baos.write((v >>> 24) & 0xFF);
    }
}
