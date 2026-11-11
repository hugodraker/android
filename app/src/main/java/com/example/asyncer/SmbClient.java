package com.example.asyncer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SmbClient {

    public static final int PROTO_AUTO = 0;
    public static final int PROTO_SMB1 = 1;
    public static final int PROTO_SMB2 = 2;

    public static final int SMB2_PORT = 445;
    public static final int SMB_BUFFER_SIZE = 131072;
    public static final int MAX_ITEMS = 65535;
    public static final int MAX_SHARES = 50;

    private static final short SMB2_DIALECT_0202 = 0x0202;
    private static final short SMB2_DIALECT_0210 = 0x0210;
    private static final short SMB2_DIALECT_0300 = 0x0300;

    private static final short SMB2_NEGOTIATE = 0x0000;
    private static final short SMB2_SESSION_SETUP = 0x0001;
    private static final short SMB2_LOGOFF = 0x0002;
    private static final short SMB2_TREE_CONNECT = 0x0003;
    private static final short SMB2_TREE_DISCONNECT = 0x0004;
    private static final short SMB2_CREATE = 0x0005;
    private static final short SMB2_CLOSE = 0x0006;
    private static final short SMB2_READ = 0x0008;
    private static final short SMB2_WRITE = 0x0009;
    private static final short SMB2_QUERY_DIRECTORY = 0x000E;
    private static final short SMB2_SET_INFO = 0x0011;

    private static final int STATUS_SUCCESS = 0x00000000;
    private static final int STATUS_MORE_PROCESSING = 0xC0000016;
    private static final int STATUS_OBJECT_NAME_COLLISION = 0xC0000035;
    private static final int STATUS_BUFFER_OVERFLOW = 0x80000005;

    public interface ProgressListener {
        void onProgress(int percent);
        void onLog(String message);
    }

    public static class DirectoryItem {
        public String path;
        public boolean isDir;
        public long mtime; // Windows FILETIME (100ns intervals since 1601)
        public long size;
        public String date;

        public DirectoryItem(String path, boolean isDir, long mtime, long size) {
            this.path = path;
            this.isDir = isDir;
            this.size = size;
            setItemDate(mtime);
        }

        public void setItemDate(long filetime) {
            this.mtime = filetime;
            if (filetime == 0) {
                this.date = "";
                return;
            }
            long unixMs = filetimeToUnixMs(filetime);
            if (unixMs > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                this.date = sdf.format(new Date(unixMs));
            } else {
                this.date = "";
            }
        }

        public long getUnixTimeMs() {
            return filetimeToUnixMs(this.mtime);
        }
    }

    public static long filetimeToUnixMs(long filetime) {
        if (filetime <= 0) return 0;
        return (filetime / 10000L) - 11644473600000L;
    }

    public static long unixMsToFiletime(long unixMs) {
        if (unixMs <= 0) return 0;
        return (unixMs + 11644473600000L) * 10000L;
    }

    public static class ConnectionProfile {
        public String name = "Default Connection";
        public String server = "192.168.1.100";
        public String port = "445";
        public String share = "shared";
        public String user = "";
        public String pass = "";
        public int protoPref = PROTO_AUTO;
    }

    private Socket sconn;
    private DataInputStream in;
    private DataOutputStream out;
    private ProgressListener listener;

    private int currentProto = PROTO_AUTO;
    private String remoteBase = "\\";
    private String connectedServer = "";
    private String connectedShare = "";

    // SMB1 State
    private int tid = 0;
    private int uid = 0;
    private int midCounter = 1;
    private final byte[] gSessionResponse = new byte[8];

    // SMB2 State
    private long smb2SessionId = 0;
    private int smb2TreeId = 0;
    private long smb2MessageId = 0;
    private final byte[] smb2ServerGuid = new byte[16];

    private final List<DirectoryItem> remoteItems = new ArrayList<DirectoryItem>();

    public void setProgressListener(ProgressListener listener) {
        this.listener = listener;
    }

    public int getCurrentProto() {
        return currentProto;
    }

    public boolean isConnectedTo(String server, String share) {
        return sconn != null && sconn.isConnected() && !sconn.isClosed()
                && currentProto != PROTO_AUTO
                && connectedServer.equalsIgnoreCase(server)
                && connectedShare.equalsIgnoreCase(share);
    }

    public String getRemoteBase() {
        return remoteBase;
    }

    public void setRemoteBase(String remoteBase) {
        this.remoteBase = normalizePath(remoteBase);
    }

    public List<DirectoryItem> getRemoteItems() {
        return Collections.unmodifiableList(remoteItems);
    }

    private void addLog(String fmt, Object... args) {
        if (listener != null) {
            listener.onLog(String.format(Locale.US, fmt, args));
        }
    }

    private void setProgress(int percent) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    public static String normalizePath(String path) {
        if (path == null) return "\\";
        return path.replace('/', '\\');
    }

    public static String formatFolderPath(String baseDir, String newDir) {
        String cleanNew = normalizePath(newDir);
        while (cleanNew.startsWith("\\")) {
            cleanNew = cleanNew.substring(1);
        }
        if (baseDir == null || baseDir.isEmpty() || baseDir.equals("/") || baseDir.equals("\\")) {
            return "\\" + cleanNew;
        }
        String cleanBase = normalizePath(baseDir);
        while (cleanBase.endsWith("\\")) {
            cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
        }
        return cleanBase + "\\" + cleanNew;
    }

    private static byte[] utf8ToUtf16le(String src) {
        if (src == null || src.isEmpty()) return new byte[0];
        try {
            return src.getBytes("UTF-16LE");
        } catch (UnsupportedEncodingException e) {
            return new byte[0];
        }
    }

    private static byte[] getAsciiBytes(String src) {
        if (src == null || src.isEmpty()) return new byte[0];
        try {
            return src.getBytes("US-ASCII");
        } catch (UnsupportedEncodingException e) {
            return new byte[0];
        }
    }

    private static void putAsciiZ(ByteBuffer buf, String s) {
        if (s != null) {
            buf.put(getAsciiBytes(s));
        }
        buf.put((byte) 0);
    }

    private static int getCurrentPidLow() {
        try {
            Class<?> proc = Class.forName("android.os.Process");
            int pid = (Integer) proc.getMethod("myPid").invoke(null);
            return pid & 0xFFFF;
        } catch (Exception e) {
            return (int) (Thread.currentThread().getId() & 0xFFFF);
        }
    }

    /* ==========================================================================
       NETWORK & TRANSPORT
       ========================================================================== */

    private boolean connectWithTimeout(String host, int port) {
        disconnectAll();
        try {
            sconn = new Socket();
            sconn.connect(new InetSocketAddress(host, port), 1500);
            sconn.setSoTimeout(5000);
            in = new DataInputStream(sconn.getInputStream());
            out = new DataOutputStream(sconn.getOutputStream());
            return true;
        } catch (IOException e) {
            disconnectSocketOnly();
            return false;
        }
    }

    private void disconnectSocketOnly() {
        if (sconn != null) {
            try {
                sconn.close();
            } catch (IOException ignored) {
            }
            sconn = null;
            in = null;
            out = null;
        }
    }

    public void disconnectAll() {
        disconnectSocketOnly();
        currentProto = PROTO_AUTO;
        smb2SessionId = 0;
        smb2TreeId = 0;
        smb2MessageId = 0;
        tid = 0;
        uid = 0;
        connectedServer = "";
        connectedShare = "";
    }

    public void close() {
        disconnectAll();
    }

    private boolean smbSendPacket(byte[] data, int len) {
        if (out == null) return false;
        try {
            byte[] header = new byte[]{
                0,
                (byte) ((len >> 16) & 0xFF),
                (byte) ((len >> 8) & 0xFF),
                (byte) (len & 0xFF)
            };
            out.write(header);
            out.write(data, 0, len);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] smbRecvPacket() {
        if (in == null) return null;
        try {
            byte[] header = new byte[4];
            while (true) {
                in.readFully(header);
                int expected = ((header[1] & 0x01) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                if ((header[0] & 0xFF) == 0x85 && expected == 0) {
                    continue;
                }
                int toRead = Math.min(expected, SMB_BUFFER_SIZE);
                byte[] buffer = new byte[toRead];
                in.readFully(buffer);
                if (expected > toRead) {
                    in.skipBytes(expected - toRead);
                }
                return buffer;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private ByteBuffer smbBuildHeader(byte cmd) {
        ByteBuffer pkt = ByteBuffer.allocate(SMB_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        pkt.put(new byte[]{(byte) 0xFF, 'S', 'M', 'B'});
        pkt.put(cmd);
        pkt.putInt(0);
        pkt.put((byte) 0x80);
        pkt.putShort((short) 0x07C7);
        pkt.putShort((short) 0);
        pkt.put(new byte[8]);
        pkt.putShort((short) 0);
        pkt.putShort((short) tid);
        pkt.putShort((short) getCurrentPidLow());
        pkt.putShort((short) uid);
        pkt.putShort((short) (midCounter++ & 0xFFFF));
        return pkt;
    }

    private ByteBuffer smb2InitHeader(short cmd) {
        ByteBuffer pkt = ByteBuffer.allocate(SMB_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        pkt.put(new byte[]{(byte) 0xFE, 'S', 'M', 'B'});
        pkt.putShort((short) 64);
        pkt.putShort((short) (cmd == SMB2_NEGOTIATE ? 0 : 1));
        pkt.putInt(0);
        pkt.putShort(cmd);
        pkt.putShort((short) 32);
        pkt.putInt(0);
        pkt.putInt(0);
        pkt.putLong(smb2MessageId++);
        pkt.putInt(0);
        pkt.putInt(smb2TreeId);
        pkt.putLong(smb2SessionId);
        pkt.put(new byte[16]);
        return pkt;
    }

    /* ==========================================================================
       SMB1 CORE & OPERATIONS
       ========================================================================== */

    public boolean smbNegotiate() {
        ByteBuffer pkt = smbBuildHeader((byte) 0x72);
        String[] dialects = {
            "PC NETWORK PROGRAM 1.0",
            "MICROSOFT NETWORKS 3.0",
            "DOS LM1.2X002",
            "DOS LANMAN2.1",
            "Windows for Workgroups 3.1a",
            "NT LM 0.12"
        };
        pkt.put((byte) 0);
        int bccPos = pkt.position();
        pkt.putShort((short) 0);
        for (String d : dialects) {
            pkt.put((byte) 0x02);
            putAsciiZ(pkt, d);
        }
        pkt.putShort(bccPos, (short) (pkt.position() - bccPos - 2));

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 32) return false;
        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        return rb.getInt(5) == 0;
    }

    public boolean smbSession(String user, String pass) {
        ByteBuffer pkt = smbBuildHeader((byte) 0x73);
        String domain = "WORKGROUP";
        String uname = user != null ? user : "";
        if (pass == null) pass = "";

        int slash = uname.indexOf('\\');
        if (slash >= 0) {
            domain = uname.substring(0, slash);
            uname = uname.substring(slash + 1);
        }

        pkt.put((byte) 10);
        pkt.put((byte) 0xFF);
        pkt.put((byte) 0);
        pkt.putShort((short) 0);
        pkt.putShort((short) 65535);
        pkt.putShort((short) 2);
        pkt.putShort((short) 1);
        pkt.putInt(0);
        int passLen = getAsciiBytes(pass).length;
        pkt.putShort((short) (passLen > 0 ? passLen + 1 : 1));
        pkt.putInt(0);

        int bccPos = pkt.position();
        pkt.putShort((short) 0);
        int dataStart = pkt.position();

        if (passLen > 0) {
            putAsciiZ(pkt, pass);
        } else {
            pkt.put((byte) 0);
        }
        putAsciiZ(pkt, uname);
        putAsciiZ(pkt, domain);
        putAsciiZ(pkt, "Windows 5.1");
        putAsciiZ(pkt, "LAN Manager");

        pkt.putShort(bccPos, (short) (pkt.position() - dataStart));

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 32) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        uid = rb.getShort(28) & 0xFFFF;
        return rb.getInt(5) == 0;
    }

    public boolean smbTreeConnect(String server, String share) {
        ByteBuffer pkt = smbBuildHeader((byte) 0x75);
        pkt.putShort(10, (short) (pkt.getShort(10) | 0x8000));
        pkt.putShort(24, (short) 0xFFFF);
        pkt.position(14);
        pkt.put(gSessionResponse);
        pkt.position(32);

        pkt.put((byte) 4);
        pkt.put((byte) 0xFF);
        pkt.put((byte) 0);
        pkt.putShort((short) 0);
        pkt.putShort((short) 0);
        pkt.putShort((short) 1);

        String cleanShare = (share.startsWith("\\") || share.startsWith("/")) ? share.substring(1) : share;
        String path = "\\\\" + server + "\\" + cleanShare;
        byte[] pathUtf16 = utf8ToUtf16le(path);

        int byteCount = 1 + pathUtf16.length + 2 + 6;
        pkt.putShort((short) byteCount);
        pkt.put((byte) 0);
        pkt.put(pathUtf16);
        pkt.putShort((short) 0);
        putAsciiZ(pkt, "?????");

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 32) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        tid = rb.getShort(24) & 0xFFFF;
        return rb.getInt(5) == 0;
    }

    private boolean smbSimplePathCmd(byte cmd, String path) {
        ByteBuffer pkt = smbBuildHeader(cmd);
        if (cmd == 0x06) {
            pkt.put((byte) 1);
            pkt.putShort((short) 0x16);
        } else {
            pkt.put((byte) 0);
        }
        String formatted = path.startsWith("\\") ? path : "\\" + path;
        byte[] pathBytes = getAsciiBytes(formatted);
        pkt.putShort((short) (pathBytes.length + 2));
        pkt.put((byte) 0x04);
        pkt.put(pathBytes);
        pkt.put((byte) 0);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 32) return false;
        return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(5) == 0;
    }

    public boolean smbDelete(String path) {
        return smbSimplePathCmd((byte) 0x06, path);
    }

    public boolean smbDeleteDir(String path) {
        return smbSimplePathCmd((byte) 0x01, path);
    }

    public boolean smbRename(String oldp, String newp) {
        ByteBuffer pkt = smbBuildHeader((byte) 0x07);
        pkt.put((byte) 1);
        pkt.putShort((short) 0x16);
        String fOld = oldp.startsWith("\\") ? oldp : "\\" + oldp;
        String fNew = newp.startsWith("\\") ? newp : "\\" + newp;
        byte[] oldBytes = getAsciiBytes(fOld);
        byte[] newBytes = getAsciiBytes(fNew);
        pkt.putShort((short) (oldBytes.length + newBytes.length + 4));
        pkt.put((byte) 0x04);
        pkt.put(oldBytes);
        pkt.put((byte) 0);
        pkt.put((byte) 0x04);
        pkt.put(newBytes);
        pkt.put((byte) 0);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 32) return false;
        return ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(5) == 0;
    }

    public boolean smbMkdirEx(String parentDir, String dirname) {
        String targetPath = formatFolderPath(parentDir, dirname);
        ByteBuffer pkt = smbBuildHeader((byte) 0x00);
        pkt.put((byte) 0);
        byte[] pathBytes = getAsciiBytes(targetPath);
        pkt.putShort((short) (pathBytes.length + 2));
        pkt.put((byte) 0x04);
        pkt.put(pathBytes);
        pkt.put((byte) 0);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 32) return false;
        int status = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(5);
        return status == 0 || status == STATUS_OBJECT_NAME_COLLISION;
    }

    public boolean smbListDirectory() {
        ByteBuffer pkt = smbBuildHeader((byte) 0x32);
        pkt.putShort(10, (short) (pkt.getShort(10) | 0x8000));
        pkt.putShort(24, (short) tid);

        pkt.put((byte) 15);
        int vPos = pkt.position();
        int bccPos = vPos + 15 * 2;
        int dataStart = bccPos + 2;
        int paramPtr = dataStart;
        if (paramPtr % 2 != 0) paramPtr++;

        pkt.position(paramPtr);
        pkt.putShort((short) 0x0016);
        pkt.putShort((short) 256);
        pkt.putShort((short) 0x0002);
        pkt.putShort((short) 0x0104); // SMB_FIND_FILE_BOTH_DIRECTORY_INFO
        pkt.putInt(0);

        String searchPattern = (remoteBase.endsWith("\\") || remoteBase.endsWith("/"))
                ? remoteBase + "*"
                : remoteBase + "\\*";
        if (!searchPattern.startsWith("\\")) searchPattern = "\\" + searchPattern;
        searchPattern = normalizePath(searchPattern);
        byte[] patUtf16 = utf8ToUtf16le(searchPattern);
        pkt.put(patUtf16);
        pkt.putShort((short) 0);

        int endPos = pkt.position();
        int paramCount = endPos - paramPtr;
        int dataCount = 0;

        pkt.position(vPos);
        pkt.putShort((short) paramCount);
        pkt.putShort((short) dataCount);
        pkt.putShort((short) 1024);
        pkt.putShort((short) 32768);
        pkt.put((byte) 1);
        pkt.put((byte) 0);
        pkt.putShort((short) 0);
        pkt.putInt(0);
        pkt.putShort((short) 0);
        pkt.putShort((short) paramCount);
        pkt.putShort((short) paramPtr);
        pkt.putShort((short) dataCount);
        pkt.putShort((short) endPos);
        pkt.put((byte) 1);
        pkt.put((byte) 0);
        pkt.putShort((short) 0x0001);

        pkt.putShort(bccPos, (short) (endPos - dataStart));

        if (!smbSendPacket(pkt.array(), endPos)) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 33) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        if (rb.getInt(5) != 0) return false;
        int wct = rb.get(32) & 0xFF;
        if (wct < 10) return false;

        int vwvBase = 33;
        int paramOff = rb.getShort(vwvBase + 4 * 2) & 0xFFFF;
        int dataCnt = rb.getShort(vwvBase + 6 * 2) & 0xFFFF;
        int dataOff = rb.getShort(vwvBase + 7 * 2) & 0xFFFF;

        if (paramOff > 0 && dataOff > 0 && dataCnt > 0 && dataOff + dataCnt <= resp.length) {
            int dataPtr = dataOff;
            int dataEnd = dataOff + dataCnt;
            while (dataPtr + 94 <= dataEnd) {
                int nextOffset = rb.getInt(dataPtr);
                long mtime = rb.getLong(dataPtr + 24);
                long endOfFile = rb.getLong(dataPtr + 40);
                int fileAttrs = rb.getInt(dataPtr + 56);
                int nameLen = rb.getInt(dataPtr + 60);
                if (nameLen > 0 && dataPtr + 94 + nameLen <= dataEnd) {
                    String itemName = "";
                    try {
                        itemName = new String(resp, dataPtr + 94, nameLen, "UTF-16LE");
                    } catch (UnsupportedEncodingException ignored) {}
                    int nullIdx = itemName.indexOf('\0');
                    if (nullIdx >= 0) itemName = itemName.substring(0, nullIdx);
                    if (!itemName.equals(".") && !itemName.equals("..") && remoteItems.size() < MAX_ITEMS) {
                        remoteItems.add(new DirectoryItem(itemName, (fileAttrs & 0x10) != 0, mtime, endOfFile));
                    }
                }
                if (nextOffset == 0) break;
                dataPtr += nextOffset;
            }
        }
        return true;
    }

    public boolean copyR2lFile(String rpath, String lpath) {
        String formatted = rpath.startsWith("\\") ? rpath : "\\" + rpath;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(lpath);
            ByteBuffer pkt = smbBuildHeader((byte) 0xA2);
            pkt.putShort(10, (short) (pkt.getShort(10) | 0x8000));

            pkt.put((byte) 24);
            pkt.put((byte) 0xFF);
            pkt.put((byte) 0);
            pkt.putShort((short) 0);
            pkt.put((byte) 0);
            int nameLenPos = pkt.position();
            pkt.putShort((short) 0);
            pkt.putInt(0);
            pkt.putInt(0);
            pkt.putInt(0x80000000);
            pkt.putLong(0);
            pkt.putInt(0x80);
            pkt.putInt(0x01);
            pkt.putInt(0x01);
            pkt.putInt(0x40);
            pkt.putInt(0x02);
            pkt.put((byte) 0);

            int bccPos = pkt.position();
            pkt.putShort((short) 0);
            int dataStart = pkt.position();
            if (pkt.position() % 2 != 0) pkt.put((byte) 0);

            byte[] pathUtf16 = utf8ToUtf16le(formatted);
            pkt.put(pathUtf16);
            pkt.putShort((short) 0);

            pkt.putShort(nameLenPos, (short) pathUtf16.length);
            pkt.putShort(bccPos, (short) (pkt.position() - dataStart));

            if (!smbSendPacket(pkt.array(), pkt.position())) return false;
            byte[] resp = smbRecvPacket();
            if (resp == null || resp.length < 96) return false;

            ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            if (rb.getInt(5) != 0) return false;

            int fid = rb.getShort(38) & 0xFFFF;
            long fileSize = rb.getLong(88);
            long offset = 0;
            int chunkSize = 32768;

            setProgress(0);
            while (true) {
                pkt = smbBuildHeader((byte) 0x2E);
                pkt.put((byte) 12);
                pkt.put((byte) 0xFF);
                pkt.put((byte) 0);
                pkt.putShort((short) 0);
                pkt.putShort((short) fid);
                pkt.putInt((int) (offset & 0xFFFFFFFFL));
                pkt.putShort((short) chunkSize);
                pkt.putShort((short) chunkSize);
                pkt.putInt(0);
                pkt.putShort((short) 0);
                pkt.putInt((int) (offset >>> 32));
                pkt.putShort((short) 0);

                if (!smbSendPacket(pkt.array(), pkt.position())) break;
                resp = smbRecvPacket();
                if (resp == null || resp.length < 47) break;

                rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
                int status = rb.getInt(5);
                if (status != 0 && status != STATUS_BUFFER_OVERFLOW) break;

                int dataLen = rb.getShort(43) & 0xFFFF;
                int dataOff = rb.getShort(45) & 0xFFFF;
                if (dataLen == 0 || dataOff + dataLen > resp.length) break;

                fos.write(resp, dataOff, dataLen);
                offset += dataLen;
                if (fileSize > 0) {
                    setProgress((int) (((double) offset / fileSize) * 100));
                }
                if (dataLen < chunkSize) break;
            }

            pkt = smbBuildHeader((byte) 0x04);
            pkt.put((byte) 3);
            pkt.putShort((short) fid);
            pkt.putInt(0);
            pkt.putShort((short) 0);
            smbSendPacket(pkt.array(), pkt.position());
            smbRecvPacket();

            setProgress(100);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
        }
    }

    public boolean copyL2rFile(String lpath, String rpath) {
        File localFile = new File(lpath);
        if (!localFile.exists() || !localFile.isFile()) return false;
        long fileSize = localFile.length();
        String formatted = rpath.startsWith("\\") ? rpath : "\\" + rpath;

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(localFile);
            ByteBuffer pkt = smbBuildHeader((byte) 0xA2);
            pkt.putShort(10, (short) (pkt.getShort(10) | 0x8000));

            pkt.put((byte) 24);
            pkt.put((byte) 0xFF);
            pkt.put((byte) 0);
            pkt.putShort((short) 0);
            pkt.put((byte) 0);
            int nameLenPos = pkt.position();
            pkt.putShort((short) 0);
            pkt.putInt(0);
            pkt.putInt(0);
            pkt.putInt(0x40000000);
            pkt.putLong(0);
            pkt.putInt(0x80);
            pkt.putInt(0x00);
            pkt.putInt(0x05);
            pkt.putInt(0x40);
            pkt.putInt(0x02);
            pkt.put((byte) 0);

            int bccPos = pkt.position();
            pkt.putShort((short) 0);
            int dataStart = pkt.position();
            if (pkt.position() % 2 != 0) pkt.put((byte) 0);

            byte[] pathUtf16 = utf8ToUtf16le(formatted);
            pkt.put(pathUtf16);
            pkt.putShort((short) 0);

            pkt.putShort(nameLenPos, (short) pathUtf16.length);
            pkt.putShort(bccPos, (short) (pkt.position() - dataStart));

            if (!smbSendPacket(pkt.array(), pkt.position())) return false;
            byte[] resp = smbRecvPacket();
            if (resp == null || resp.length < 40) return false;

            ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            if (rb.getInt(5) != 0) return false;

            int fid = rb.getShort(38) & 0xFFFF;
            long offset = 0;
            byte[] buf = new byte[32768];
            int readBytes;

            setProgress(0);
            while ((readBytes = fis.read(buf)) > 0) {
                pkt = smbBuildHeader((byte) 0x2F);
                pkt.put((byte) 14);
                pkt.put((byte) 0xFF);
                pkt.put((byte) 0);
                pkt.putShort((short) 0);
                pkt.putShort((short) fid);
                pkt.putInt((int) (offset & 0xFFFFFFFFL));
                pkt.putInt(0);
                pkt.putShort((short) 0);
                pkt.putShort((short) 0);
                pkt.putShort((short) 0);
                pkt.putShort((short) readBytes);
                int dataOffPos = pkt.position();
                pkt.putShort((short) 0);
                pkt.putInt((int) (offset >>> 32));
                int wBccPos = pkt.position();
                pkt.putShort((short) 0);
                pkt.put((byte) 0);

                int actualDataOff = pkt.position();
                pkt.putShort(dataOffPos, (short) actualDataOff);
                pkt.put(buf, 0, readBytes);
                pkt.putShort(wBccPos, (short) (pkt.position() - wBccPos - 2));

                if (!smbSendPacket(pkt.array(), pkt.position())) break;
                resp = smbRecvPacket();
                if (resp == null || resp.length < 32) break;
                if (ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(5) != 0) break;

                offset += readBytes;
                if (fileSize > 0) {
                    setProgress((int) (((double) offset / fileSize) * 100));
                }
            }

            pkt = smbBuildHeader((byte) 0x04);
            pkt.put((byte) 3);
            pkt.putShort((short) fid);
            pkt.putInt(0);
            pkt.putShort((short) 0);
            smbSendPacket(pkt.array(), pkt.position());
            smbRecvPacket();

            setProgress(100);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ignored) {}
        }
    }

    /* ==========================================================================
       SMB2 CORE & OPERATIONS
       ========================================================================== */

    public boolean smb2Negotiate() {
        ByteBuffer pkt = smb2InitHeader(SMB2_NEGOTIATE);
        pkt.putShort((short) 36);
        pkt.putShort((short) 3);
        pkt.putShort((short) 1);
        pkt.putShort((short) 0);
        pkt.putInt(0);
        byte[] clientGuid = new byte[16];
        new SecureRandom().nextBytes(clientGuid);
        pkt.put(clientGuid);
        pkt.putInt(0);
        pkt.putShort((short) 0);
        pkt.putShort((short) 0);

        pkt.putShort(SMB2_DIALECT_0202);
        pkt.putShort(SMB2_DIALECT_0210);
        pkt.putShort(SMB2_DIALECT_0300);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 96) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        int status = rb.getInt(8);
        if (status != STATUS_SUCCESS) return false;

        System.arraycopy(resp, 64 + 8, smb2ServerGuid, 0, 16);
        return true;
    }

    public boolean smb2SessionSetup(String user, String pass) {
        String domain = "";
        String uname = user != null ? user : "";
        if (pass == null) pass = "";

        int slash = uname.indexOf('\\');
        if (slash >= 0) {
            domain = uname.substring(0, slash);
            uname = uname.substring(slash + 1);
        }

        byte[] type1Token = NtlmEngine.createType1Message(domain);

        ByteBuffer pkt = smb2InitHeader(SMB2_SESSION_SETUP);
        pkt.putShort((short) 25);
        pkt.put((byte) 0);
        pkt.put((byte) 1);
        pkt.putInt(0);
        pkt.putInt(0);
        short secOffset = (short) (64 + 24);
        pkt.putShort(secOffset);
        pkt.putShort((short) type1Token.length);
        pkt.putLong(0);
        pkt.put(type1Token);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] response = smbRecvPacket();
        if (response == null || response.length < 72) return false;

        ByteBuffer rb = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        int status = rb.getInt(8);
        if (status == STATUS_SUCCESS) {
            smb2SessionId = rb.getLong(40);
            return true;
        }
        if (status != STATUS_MORE_PROCESSING) return false;

        smb2SessionId = rb.getLong(40);
        int challengeSecOffset = rb.getShort(68) & 0xFFFF;
        int challengeSecLen = rb.getShort(70) & 0xFFFF;
        if (challengeSecOffset < 72 || challengeSecOffset + challengeSecLen > response.length) {
            return false;
        }

        byte[] challengeToken = Arrays.copyOfRange(response, challengeSecOffset, challengeSecOffset + challengeSecLen);
        byte[] type3Token = NtlmEngine.createType3Message(uname, pass, domain, challengeToken);
        if (type3Token == null) return false;

        pkt = smb2InitHeader(SMB2_SESSION_SETUP);
        pkt.putLong(40, smb2SessionId);
        pkt.putShort((short) 25);
        pkt.put((byte) 0);
        pkt.put((byte) 1);
        pkt.putInt(0);
        pkt.putInt(0);
        pkt.putShort(secOffset);
        pkt.putShort((short) type3Token.length);
        pkt.putLong(0);
        pkt.put(type3Token);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        response = smbRecvPacket();
        if (response == null || response.length < 64) return false;

        rb = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
        return rb.getInt(8) == STATUS_SUCCESS;
    }

    public boolean smb2TreeConnect(String server, String share) {
        ByteBuffer pkt = smb2InitHeader(SMB2_TREE_CONNECT);
        pkt.putShort((short) 9);
        pkt.putShort((short) 0);

        String cleanShare = (share.startsWith("\\") || share.startsWith("/")) ? share.substring(1) : share;
        String fullPath = "\\\\" + server + "\\" + cleanShare;
        byte[] pathUtf16 = utf8ToUtf16le(fullPath);

        short pathOffset = (short) (64 + 8);
        pkt.putShort(pathOffset);
        pkt.putShort((short) pathUtf16.length);
        pkt.put(pathUtf16);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 64) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        if (rb.getInt(8) != STATUS_SUCCESS) return false;
        smb2TreeId = rb.getInt(36);
        return true;
    }

    public boolean smb2TreeDisconnect() {
        ByteBuffer pkt = smb2InitHeader(SMB2_TREE_DISCONNECT);
        pkt.putShort((short) 4);
        pkt.putShort((short) 0);
        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        return smbRecvPacket() != null;
    }

    private void putSmb2CreateReq(
            ByteBuffer pkt,
            int desiredAccess,
            int fileAttributes,
            int shareAccess,
            int createDisposition,
            int createOptions,
            String relPath) {
        pkt.putShort((short) 57);
        pkt.put((byte) 0);
        pkt.put((byte) 0);
        pkt.putInt(2);
        pkt.putLong(0);
        pkt.putLong(0);
        pkt.putInt(desiredAccess);
        pkt.putInt(fileAttributes);
        pkt.putInt(shareAccess);
        pkt.putInt(createDisposition);
        pkt.putInt(createOptions);

        byte[] nameUtf16 = utf8ToUtf16le(relPath);
        pkt.putShort((short) 120);
        pkt.putShort((short) nameUtf16.length);
        pkt.putInt(0);
        pkt.putInt(0);

        if (nameUtf16.length > 0) {
            pkt.put(nameUtf16);
        } else {
            pkt.putShort((short) 0);
        }
    }

    private void smb2CloseHandle(long fidPers, long fidVol) {
        ByteBuffer pkt = smb2InitHeader(SMB2_CLOSE);
        pkt.putShort((short) 24);
        pkt.putShort((short) 0);
        pkt.putInt(0);
        pkt.putLong(fidPers);
        pkt.putLong(fidVol);
        if (smbSendPacket(pkt.array(), pkt.position())) {
            smbRecvPacket();
        }
    }

    public boolean smb2ListDirectory() {
        ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
        String relPath = "";
        if (remoteBase != null && !remoteBase.isEmpty() && !remoteBase.equals("\\") && !remoteBase.equals("/")) {
            relPath = remoteBase;
            while (relPath.startsWith("\\") || relPath.startsWith("/")) {
                relPath = relPath.substring(1);
            }
            while (relPath.endsWith("\\") || relPath.endsWith("/")) {
                relPath = relPath.substring(0, relPath.length() - 1);
            }
        }

        putSmb2CreateReq(pkt, 0x00100081, 0x00000010, 0x00000007, 1, 0x00000021, relPath);
        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 152) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        if (rb.getInt(8) != STATUS_SUCCESS) return false;

        long fileIdPers = rb.getLong(64 + 64);
        long fileIdVol = rb.getLong(64 + 72);
        boolean keepQuerying = true;
        boolean firstQuery = true;

        while (keepQuerying && remoteItems.size() < MAX_ITEMS) {
            pkt = smb2InitHeader(SMB2_QUERY_DIRECTORY);
            pkt.putShort((short) 33);
            pkt.put((byte) 37); // FileIdBothDirectoryInformation
            pkt.put((byte) (firstQuery ? 0x01 : 0x00));
            firstQuery = false;
            pkt.putInt(0);
            pkt.putLong(fileIdPers);
            pkt.putLong(fileIdVol);
            pkt.putShort((short) 96);
            pkt.putShort((short) 2);
            pkt.putInt(65536);
            pkt.putShort((short) '*');

            if (!smbSendPacket(pkt.array(), pkt.position())) break;
            resp = smbRecvPacket();
            if (resp == null || resp.length < 72) break;

            rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            if (rb.getInt(8) == STATUS_SUCCESS) {
                int dataOff = rb.getShort(64 + 2) & 0xFFFF;
                int dataLen = rb.getInt(64 + 4);
                if (dataOff > 0 && dataLen > 0 && dataOff + dataLen <= resp.length) {
                    int dataPtr = dataOff;
                    int dataEnd = dataOff + dataLen;
                    while (dataPtr + 104 <= dataEnd) {
                        int nextOffset = rb.getInt(dataPtr);
                        long mtime = rb.getLong(dataPtr + 24);
                        long endOfFile = rb.getLong(dataPtr + 40);
                        int fileAttrs = rb.getInt(dataPtr + 56);
                        int nameLen = rb.getInt(dataPtr + 60);
                        if (nameLen > 0 && dataPtr + 104 + nameLen <= dataEnd) {
                            String itemName = "";
                            try {
                                itemName = new String(resp, dataPtr + 104, nameLen, "UTF-16LE");
                            } catch (UnsupportedEncodingException ignored) {}
                            int nullIdx = itemName.indexOf('\0');
                            if (nullIdx >= 0) itemName = itemName.substring(0, nullIdx);
                            if (!itemName.equals(".") && !itemName.equals("..") && remoteItems.size() < MAX_ITEMS) {
                                remoteItems.add(new DirectoryItem(itemName, (fileAttrs & 0x10) != 0, mtime, endOfFile));
                            }
                        }
                        if (nextOffset == 0) break;
                        dataPtr += nextOffset;
                    }
                } else {
                    keepQuerying = false;
                }
            } else {
                keepQuerying = false;
            }
        }

        smb2CloseHandle(fileIdPers, fileIdVol);
        return true;
    }

    public boolean smb2Mkdir(String rpath) {
        String relPath = normalizePath(rpath);
        while (relPath.startsWith("\\")) relPath = relPath.substring(1);
        if (relPath.isEmpty()) return true;

        ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
        putSmb2CreateReq(pkt, 0x00120116, 0x00000010, 0x07, 2, 0x00000021, relPath);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 152) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        int status = rb.getInt(8);
        if (status == STATUS_SUCCESS) {
            long fidPers = rb.getLong(64 + 64);
            long fidVol = rb.getLong(64 + 72);
            smb2CloseHandle(fidPers, fidVol);
            return true;
        }
        return status == STATUS_OBJECT_NAME_COLLISION;
    }

    public boolean smb2DeleteInternal(String rpath, boolean isDir) {
        String relPath = normalizePath(rpath);
        while (relPath.startsWith("\\")) relPath = relPath.substring(1);

        ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
        int createOptions = 0x00001000 | (isDir ? 0x00000021 : 0);
        putSmb2CreateReq(pkt, 0x00010000, 0, 0x07, 1, createOptions, relPath);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 152) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        if (rb.getInt(8) == STATUS_SUCCESS) {
            long fidPers = rb.getLong(64 + 64);
            long fidVol = rb.getLong(64 + 72);
            smb2CloseHandle(fidPers, fidVol);
            return true;
        }
        return false;
    }

    public boolean smb2Rename(String oldp, String newp) {
        String relOld = normalizePath(oldp);
        while (relOld.startsWith("\\")) relOld = relOld.substring(1);
        String relNew = normalizePath(newp);
        while (relNew.startsWith("\\")) relNew = relNew.substring(1);

        ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
        putSmb2CreateReq(pkt, 0x00110080, 0, 0x07, 1, 0, relOld);

        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 152) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        if (rb.getInt(8) == STATUS_SUCCESS) {
            long fidPers = rb.getLong(64 + 64);
            long fidVol = rb.getLong(64 + 72);

            byte[] newUtf16 = utf8ToUtf16le(relNew);
            int bufferLength = 20 + newUtf16.length;

            pkt = smb2InitHeader(SMB2_SET_INFO);
            pkt.putShort((short) 33);
            pkt.put((byte) 1);
            pkt.put((byte) 10); // FileRenameInformation
            pkt.putInt(bufferLength);
            pkt.putShort((short) 96);
            pkt.putShort((short) 0);
            pkt.putInt(0);
            pkt.putLong(fidPers);
            pkt.putLong(fidVol);

            pkt.put((byte) 1); // ReplaceIfExists = 1
            pkt.put(new byte[15]);
            pkt.putInt(newUtf16.length);
            pkt.put(newUtf16);

            smbSendPacket(pkt.array(), pkt.position());
            smbRecvPacket();

            smb2CloseHandle(fidPers, fidVol);
            return true;
        }
        return false;
    }

    public boolean smb2SetFileTime(String rpath, long unixMs) {
        String relPath = normalizePath(rpath);
        while (relPath.startsWith("\\")) relPath = relPath.substring(1);

        ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
        putSmb2CreateReq(pkt, 0x00000100, 0, 0x07, 1, 0x00000040, relPath); // FILE_WRITE_ATTRIBUTES
        if (!smbSendPacket(pkt.array(), pkt.position())) return false;
        byte[] resp = smbRecvPacket();
        if (resp == null || resp.length < 152) return false;

        ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
        if (rb.getInt(8) != STATUS_SUCCESS) return false;

        long fidPers = rb.getLong(64 + 64);
        long fidVol = rb.getLong(64 + 72);
        long ft = unixMsToFiletime(unixMs);

        pkt = smb2InitHeader(SMB2_SET_INFO);
        pkt.putShort((short) 33);
        pkt.put((byte) 1);  // SMB2_0_INFO_FILE
        pkt.put((byte) 4);  // FileBasicInformation
        pkt.putInt(40);
        pkt.putShort((short) 96);
        pkt.putShort((short) 0);
        pkt.putInt(0);
        pkt.putLong(fidPers);
        pkt.putLong(fidVol);

        pkt.putLong(0);  // CreationTime
        pkt.putLong(ft); // LastAccessTime
        pkt.putLong(ft); // LastWriteTime
        pkt.putLong(ft); // ChangeTime
        pkt.putInt(0);   // FileAttributes
        pkt.putInt(0);   // Reserved

        smbSendPacket(pkt.array(), pkt.position());
        smbRecvPacket();
        smb2CloseHandle(fidPers, fidVol);
        return true;
    }

    public boolean smb2CopyR2lFile(String rpath, String lpath) {
        String relPath = normalizePath(rpath);
        while (relPath.startsWith("\\")) relPath = relPath.substring(1);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(lpath);
            ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
            putSmb2CreateReq(pkt, 0x80000000, 0, 0x01, 1, 0x00000040, relPath);

            if (!smbSendPacket(pkt.array(), pkt.position())) return false;
            byte[] resp = smbRecvPacket();
            if (resp == null || resp.length < 152) return false;

            ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            if (rb.getInt(8) != STATUS_SUCCESS) return false;

            long fileSize = rb.getLong(64 + 48);
            long fidPers = rb.getLong(64 + 64);
            long fidVol = rb.getLong(64 + 72);
            long offset = 0;
            int chunk = 65536;

            setProgress(0);
            while (true) {
                pkt = smb2InitHeader(SMB2_READ);
                pkt.putShort((short) 49);
                pkt.put((byte) 0);
                pkt.put((byte) 0);
                pkt.putInt(chunk);
                pkt.putLong(offset);
                pkt.putLong(fidPers);
                pkt.putLong(fidVol);
                pkt.putInt(0);
                pkt.putInt(0);
                pkt.putInt(0);
                pkt.putShort((short) 0);
                pkt.putShort((short) 0);
                pkt.put((byte) 0);

                if (!smbSendPacket(pkt.array(), 64 + 49)) break;
                resp = smbRecvPacket();
                if (resp == null || resp.length < 80) break;

                rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
                if (rb.getInt(8) != STATUS_SUCCESS) break;

                int dataOffset = rb.getShort(64 + 2) & 0xFFFF;
                int dataLength = rb.getInt(64 + 4);
                if (dataLength == 0 || dataOffset + dataLength > resp.length) break;

                fos.write(resp, dataOffset, dataLength);
                offset += dataLength;
                if (fileSize > 0) {
                    setProgress((int) (((double) offset / fileSize) * 100));
                }
                if (dataLength < chunk) break;
            }

            smb2CloseHandle(fidPers, fidVol);
            setProgress(100);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ignored) {}
        }
    }

    public boolean smb2CopyL2rFile(String lpath, String rpath) {
        File localFile = new File(lpath);
        if (!localFile.exists() || !localFile.isFile()) return false;
        long fileSize = localFile.length();

        String relPath = normalizePath(rpath);
        while (relPath.startsWith("\\")) relPath = relPath.substring(1);

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(localFile);
            ByteBuffer pkt = smb2InitHeader(SMB2_CREATE);
            putSmb2CreateReq(pkt, 0x40000000, 0x00000080, 0x00, 5, 0x00000040, relPath);

            if (!smbSendPacket(pkt.array(), pkt.position())) return false;
            byte[] resp = smbRecvPacket();
            if (resp == null || resp.length < 152) return false;

            ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            if (rb.getInt(8) != STATUS_SUCCESS) return false;

            long fidPers = rb.getLong(64 + 64);
            long fidVol = rb.getLong(64 + 72);
            long offset = 0;
            byte[] buf = new byte[65536];
            int readBytes;

            setProgress(0);
            while ((readBytes = fis.read(buf)) > 0) {
                pkt = smb2InitHeader(SMB2_WRITE);
                pkt.putShort((short) 49);
                pkt.putShort((short) 112);
                pkt.putInt(readBytes);
                pkt.putLong(offset);
                pkt.putLong(fidPers);
                pkt.putLong(fidVol);
                pkt.putInt(0);
                pkt.putInt(0);
                pkt.putShort((short) 0);
                pkt.putShort((short) 0);
                pkt.putInt(0);
                pkt.put(buf, 0, readBytes);

                if (!smbSendPacket(pkt.array(), pkt.position())) break;
                resp = smbRecvPacket();
                if (resp == null || resp.length < 64) break;
                if (ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt(8) != STATUS_SUCCESS) break;

                offset += readBytes;
                if (fileSize > 0) {
                    setProgress((int) (((double) offset / fileSize) * 100));
                }
            }

            smb2CloseHandle(fidPers, fidVol);
            setProgress(100);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ignored) {}
        }
    }

    /* ==========================================================================
       HIGH-LEVEL CONNECTION & UNIFIED FILE OPERATIONS
       ========================================================================== */

    public boolean connectServer(ConnectionProfile p) {
        disconnectAll();
        int port = SMB2_PORT;
        if (p.port != null && !p.port.trim().isEmpty()) {
            try {
                port = Integer.parseInt(p.port.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        String fullShare = normalizePath(p.share != null ? p.share : "");
        String shareRoot = "";
        String subDir = "\\";

        int firstSlash = fullShare.indexOf('\\');
        if (firstSlash == 0) {
            firstSlash = fullShare.indexOf('\\', 1);
        }
        if (firstSlash > 0) {
            int start = fullShare.startsWith("\\") ? 1 : 0;
            shareRoot = fullShare.substring(start, firstSlash);
            subDir = fullShare.substring(firstSlash);
        } else {
            shareRoot = fullShare.startsWith("\\") ? fullShare.substring(1) : fullShare;
        }

        boolean trySmb2 = (p.protoPref == PROTO_SMB2 || p.protoPref == PROTO_AUTO);
        boolean trySmb1 = (p.protoPref == PROTO_SMB1 || p.protoPref == PROTO_AUTO);

        if (trySmb2) {
            if (connectWithTimeout(p.server, port)) {
                addLog("Connecting via SMB2 to %s\\%s...", p.server, shareRoot);
                if (smb2Negotiate() && smb2SessionSetup(p.user, p.pass) && smb2TreeConnect(p.server, shareRoot)) {
                    currentProto = PROTO_SMB2;
                    remoteBase = subDir;
                    connectedServer = p.server;
                    connectedShare = shareRoot;
                    return true;
                }
                disconnectSocketOnly();
            }
        }

        if (trySmb1) {
            if (connectWithTimeout(p.server, port)) {
                addLog("Connecting via SMB1 to %s\\%s...", p.server, shareRoot);
                uid = 0;
                tid = 0;
                midCounter = 1;
                if (smbNegotiate()) {
                    boolean authSuccess = smbSession(p.user, p.pass);
                    if (!authSuccess && ((p.user != null && !p.user.isEmpty()) || (p.pass != null && !p.pass.isEmpty()))) {
                        disconnectSocketOnly();
                        if (connectWithTimeout(p.server, port)) {
                            uid = 0;
                            tid = 0;
                            midCounter = 1;
                            if (smbNegotiate()) {
                                authSuccess = smbSession("", "");
                            }
                        }
                    }
                    if (authSuccess && smbTreeConnect(p.server, shareRoot)) {
                        currentProto = PROTO_SMB1;
                        remoteBase = subDir;
                        connectedServer = p.server;
                        connectedShare = shareRoot;
                        return true;
                    }
                }
                disconnectSocketOnly();
            }
        }

        disconnectAll();
        return false;
    }

    public List<DirectoryItem> listRemote() {
        remoteItems.clear();
        remoteItems.add(new DirectoryItem(".", true, 0, 0));
        remoteItems.add(new DirectoryItem("..", true, 0, 0));

        if (currentProto == PROTO_SMB2) {
            smb2ListDirectory();
        } else if (currentProto == PROTO_SMB1) {
            smbListDirectory();
        }
        return getRemoteItems();
    }

    public void mkdirRecursive(String rpath) {
        String clean = normalizePath(rpath);
        while (clean.startsWith("\\")) clean = clean.substring(1);
        while (clean.endsWith("\\")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) return;

        String[] parts = clean.split("\\\\");
        String current = "";
        for (String part : parts) {
            if (part.isEmpty()) continue;
            current = current.isEmpty() ? part : current + "\\" + part;
            createRemoteDir(current);
        }
    }

    public boolean createRemoteDir(String rpath) {
        if (currentProto == PROTO_SMB1) return smbMkdirEx("", rpath);
        if (currentProto == PROTO_SMB2) return smb2Mkdir(rpath);
        return false;
    }

    public boolean deleteRemote(String rpath, boolean isDir) {
        if (currentProto == PROTO_SMB1) return isDir ? smbDeleteDir(rpath) : smbDelete(rpath);
        if (currentProto == PROTO_SMB2) return smb2DeleteInternal(rpath, isDir);
        return false;
    }

    public boolean renameRemote(String oldPath, String newPath) {
        if (currentProto == PROTO_SMB1) return smbRename(oldPath, newPath);
        if (currentProto == PROTO_SMB2) return smb2Rename(oldPath, newPath);
        return false;
    }

    public boolean setRemoteFileTime(String rpath, long unixMs) {
        if (currentProto == PROTO_SMB2) return smb2SetFileTime(rpath, unixMs);
        return false;
    }

    public boolean copySingleR2l(String rpath, String lpath) {
        if (currentProto == PROTO_SMB1) return copyR2lFile(rpath, lpath);
        if (currentProto == PROTO_SMB2) return smb2CopyR2lFile(rpath, lpath);
        return false;
    }

    public boolean copySingleL2r(String lpath, String rpath) {
        if (currentProto == PROTO_SMB1) return copyL2rFile(lpath, rpath);
        if (currentProto == PROTO_SMB2) return smb2CopyL2rFile(lpath, rpath);
        return false;
    }

    public List<DirectoryItem> fetchRemoteDirContents(String rpath) {
        String savedBase = this.remoteBase;
        List<DirectoryItem> savedItems = new ArrayList<DirectoryItem>(this.remoteItems);

        this.remoteBase = normalizePath(rpath);
        listRemote();
        List<DirectoryItem> fetched = new ArrayList<DirectoryItem>(this.remoteItems);

        this.remoteBase = savedBase;
        this.remoteItems.clear();
        this.remoteItems.addAll(savedItems);
        return fetched;
    }

    /* ==========================================================================
       PURE-JAVA NTLMv2 ENGINE
       ========================================================================== */

    private static class NtlmEngine {
        private static final byte[] NTLMSSP_SIG = new byte[]{'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};
        private static final int FLAGS_TYPE1 = 0x62088215;

        static byte[] createType1Message(String domain) {
            ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(NTLMSSP_SIG);
            buf.putInt(1);
            buf.putInt(FLAGS_TYPE1);
            buf.putShort((short) 0);
            buf.putShort((short) 0);
            buf.putInt(32);
            buf.putShort((short) 0);
            buf.putShort((short) 0);
            buf.putInt(32);
            return Arrays.copyOf(buf.array(), buf.position());
        }

        static byte[] createType3Message(String user, String password, String domain, byte[] challengePacket) {
            int sigPos = indexOf(challengePacket, NTLMSSP_SIG);
            if (sigPos < 0 || sigPos + 32 > challengePacket.length) return null;

            ByteBuffer cb = ByteBuffer.wrap(challengePacket, sigPos, challengePacket.length - sigPos)
                    .slice()
                    .order(ByteOrder.LITTLE_ENDIAN);

            if (cb.getInt(8) != 2) return null;
            int flags = cb.getInt(20);
            byte[] serverChallenge = new byte[8];
            cb.position(24);
            cb.get(serverChallenge);

            byte[] targetInfo = new byte[0];
            if ((flags & 0x00800000) != 0 && cb.capacity() >= 48) {
                int tiLen = cb.getShort(40) & 0xFFFF;
                int tiOff = cb.getInt(44);
                if (tiLen > 0 && tiOff >= 0 && tiOff + tiLen <= cb.capacity()) {
                    targetInfo = new byte[tiLen];
                    cb.position(tiOff);
                    cb.get(targetInfo);
                }
            }

            boolean isAnonymous = (user == null || user.isEmpty()) && (password == null || password.isEmpty());
            byte[] domainBytes = utf8ToUtf16le(domain != null ? domain : "");
            byte[] userBytes = utf8ToUtf16le(user != null ? user : "");
            byte[] workstationBytes = utf8ToUtf16le("ANDROID");

            byte[] lmResponse;
            byte[] ntResponse;

            if (isAnonymous) {
                lmResponse = new byte[1];
                ntResponse = new byte[0];
            } else {
                byte[] clientChallenge = new byte[8];
                new SecureRandom().nextBytes(clientChallenge);
                byte[] ntlmv2Hash = ntowfv2(user, password, domain);
                ntResponse = computeNtlmv2Response(ntlmv2Hash, serverChallenge, clientChallenge, targetInfo);
                lmResponse = computeLmv2Response(ntlmv2Hash, serverChallenge, clientChallenge);
            }

            int bodyOffset = 64;
            int lmOffset = bodyOffset;
            int ntOffset = lmOffset + lmResponse.length;
            int domOffset = ntOffset + ntResponse.length;
            int userOffset = domOffset + domainBytes.length;
            int wsOffset = userOffset + userBytes.length;
            int totalLen = wsOffset + workstationBytes.length;

            ByteBuffer out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
            out.put(NTLMSSP_SIG);
            out.putInt(3);

            out.putShort((short) lmResponse.length);
            out.putShort((short) lmResponse.length);
            out.putInt(lmOffset);

            out.putShort((short) ntResponse.length);
            out.putShort((short) ntResponse.length);
            out.putInt(ntOffset);

            out.putShort((short) domainBytes.length);
            out.putShort((short) domainBytes.length);
            out.putInt(domOffset);

            out.putShort((short) userBytes.length);
            out.putShort((short) userBytes.length);
            out.putInt(userOffset);

            out.putShort((short) workstationBytes.length);
            out.putShort((short) workstationBytes.length);
            out.putInt(wsOffset);

            out.putShort((short) 0);
            out.putShort((short) 0);
            out.putInt(totalLen);

            out.putInt(flags & ~0x40000000);

            out.put(lmResponse);
            out.put(ntResponse);
            out.put(domainBytes);
            out.put(userBytes);
            out.put(workstationBytes);

            return out.array();
        }

        private static byte[] ntowfv2(String user, String password, String domain) {
            byte[] ntHash = md4(utf8ToUtf16le(password != null ? password : ""));
            String identity = (user != null ? user.toUpperCase(Locale.US) : "") + (domain != null ? domain : "");
            return hmacMd5(ntHash, utf8ToUtf16le(identity));
        }

        private static byte[] computeNtlmv2Response(byte[] ntlmv2Hash, byte[] serverChallenge, byte[] clientChallenge, byte[] targetInfo) {
            long windowsTime = (System.currentTimeMillis() + 11644473600000L) * 10000L;
            if (targetInfo == null || targetInfo.length == 0) {
                targetInfo = new byte[4];
            }
            ByteBuffer blob = ByteBuffer.allocate(28 + targetInfo.length + 4).order(ByteOrder.LITTLE_ENDIAN);
            blob.put((byte) 0x01);
            blob.put((byte) 0x01);
            blob.putShort((short) 0);
            blob.putInt(0);
            blob.putLong(windowsTime);
            blob.put(clientChallenge);
            blob.putInt(0);
            blob.put(targetInfo);
            blob.putInt(0);

            byte[] blobBytes = blob.array();
            byte[] challengeAndBlob = new byte[8 + blobBytes.length];
            System.arraycopy(serverChallenge, 0, challengeAndBlob, 0, 8);
            System.arraycopy(blobBytes, 0, challengeAndBlob, 8, blobBytes.length);

            byte[] ntProofStr = hmacMd5(ntlmv2Hash, challengeAndBlob);
            byte[] result = new byte[16 + blobBytes.length];
            System.arraycopy(ntProofStr, 0, result, 0, 16);
            System.arraycopy(blobBytes, 0, result, 16, blobBytes.length);
            return result;
        }

        private static byte[] computeLmv2Response(byte[] ntlmv2Hash, byte[] serverChallenge, byte[] clientChallenge) {
            byte[] data = new byte[16];
            System.arraycopy(serverChallenge, 0, data, 0, 8);
            System.arraycopy(clientChallenge, 0, data, 8, 8);
            byte[] mac = hmacMd5(ntlmv2Hash, data);
            byte[] result = new byte[24];
            System.arraycopy(mac, 0, result, 0, 16);
            System.arraycopy(clientChallenge, 0, result, 16, 8);
            return result;
        }

        private static byte[] hmacMd5(byte[] key, byte[] data) {
            try {
                Mac mac = Mac.getInstance("HmacMD5");
                mac.init(new SecretKeySpec(key, "HmacMD5"));
                return mac.doFinal(data);
            } catch (Exception e) {
                return new byte[16];
            }
        }

        private static int indexOf(byte[] outer, byte[] inner) {
            for (int i = 0; i <= outer.length - inner.length; i++) {
                boolean match = true;
                for (int j = 0; j < inner.length; j++) {
                    if (outer[i + j] != inner[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) return i;
            }
            return -1;
        }

        private static byte[] md4(byte[] input) {
            int origLen = input.length;
            long bitLen = (long) origLen * 8L;
            int padLen = ((origLen + 8) / 64 + 1) * 64;
            ByteBuffer padded = ByteBuffer.allocate(padLen).order(ByteOrder.LITTLE_ENDIAN);
            padded.put(input);
            padded.put((byte) 0x80);
            padded.position(padLen - 8);
            padded.putLong(bitLen);

            int a = 0x67452301;
            int b = 0xEFCDAB89;
            int c = 0x98BADCFE;
            int d = 0x10325476;

            int[] x = new int[16];
            padded.position(0);
            while (padded.hasRemaining()) {
                for (int i = 0; i < 16; i++) x[i] = padded.getInt();
                int aa = a, bb = b, cc = c, dd = d;

                int[] r1 = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
                int[] s1 = {3, 7, 11, 19};
                for (int i = 0; i < 16; i++) {
                    int f = (b & c) | (~b & d);
                    int tmp = Integer.rotateLeft(a + f + x[r1[i]], s1[i % 4]);
                    a = d; d = c; c = b; b = tmp;
                }

                int[] r2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
                int[] s2 = {3, 5, 9, 13};
                for (int i = 0; i < 16; i++) {
                    int g = (b & c) | (b & d) | (c & d);
                    int tmp = Integer.rotateLeft(a + g + x[r2[i]] + 0x5A827999, s2[i % 4]);
                    a = d; d = c; c = b; b = tmp;
                }

                int[] r3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
                int[] s3 = {3, 9, 11, 15};
                for (int i = 0; i < 16; i++) {
                    int h = b ^ c ^ d;
                    int tmp = Integer.rotateLeft(a + h + x[r3[i]] + 0x6ED9EBA1, s3[i % 4]);
                    a = d; d = c; c = b; b = tmp;
                }

                a += aa; b += bb; c += cc; d += dd;
            }

            ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(a).putInt(b).putInt(c).putInt(d);
            return out.array();
        }
    }
}
