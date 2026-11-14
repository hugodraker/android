package com.example.asyncer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public class FtpClient {

    public static class FtpItem {
        public String name = "";
        public String path = "";
        public boolean isDirectory = false;
        public long size = 0;
        public long lastModified = 0;
    }

    private Socket ctrlSocket;
    private BufferedReader reader;
    private OutputStream writer;
    private String connectedHost = "";
    private int connectedPort = 21;
    private int timeoutMs = 8000;
    private boolean loggedIn = false;

    public synchronized boolean connect(String host, int port, String user, String pass) {
        disconnect();
        if (host == null || host.trim().length() == 0) return false;
        this.connectedHost = host.trim();
        this.connectedPort = port > 0 ? port : 21;
        String u = (user == null || user.trim().length() == 0) ? "anonymous" : user.trim();
        String p = (pass == null) ? "guest@" : pass;

        try {
            ctrlSocket = new Socket();
            ctrlSocket.connect(new InetSocketAddress(connectedHost, connectedPort), timeoutMs);
            ctrlSocket.setSoTimeout(timeoutMs);
            reader = new BufferedReader(new InputStreamReader(ctrlSocket.getInputStream(), "UTF-8"));
            writer = ctrlSocket.getOutputStream();

            int code = readReplyCode();
            if (code != 220) {
                disconnect();
                return false;
            }

            sendCmd("USER " + u);
            code = readReplyCode();
            if (code == 331) {
                sendCmd("PASS " + p);
                code = readReplyCode();
            }
            if (code != 230 && code != 202) {
                disconnect();
                return false;
            }

            // Binary mode + UTF8
            sendCmd("TYPE I");
            readReplyCode();
            sendCmd("OPTS UTF8 ON");
            readReplyCode();

            loggedIn = true;
            return true;
        } catch (Exception e) {
            disconnect();
            return false;
        }
    }

    public synchronized boolean isConnected() {
        return loggedIn && ctrlSocket != null && ctrlSocket.isConnected() && !ctrlSocket.isClosed();
    }

    public synchronized void disconnect() {
        loggedIn = false;
        try {
            if (writer != null) {
                writer.write("QUIT\r\n".getBytes("UTF-8"));
                writer.flush();
            }
        } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
        reader = null;
        writer = null;
        ctrlSocket = null;
    }

    public static String normalizeFtpPath(String path) {
        if (path == null || path.trim().length() == 0) return "/";
        String p = path.replace('\\', '/').trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public synchronized List<String> probeRootDirectories(String basePath) throws Exception {
        List<String> dirs = new ArrayList<String>();
        String norm = normalizeFtpPath(basePath);
        List<FtpItem> items = listSingleDirectory(norm, "");
        for (int i = 0; i < items.size(); i++) {
            FtpItem it = items.get(i);
            if (it.isDirectory) {
                String full = "/".equals(norm) ? ("/" + it.name) : (norm + "/" + it.name);
                dirs.add(full);
            }
        }
        if (dirs.isEmpty()) {
            dirs.add("/");
        }
        return dirs;
    }

    public synchronized List<FtpItem> fetchRemoteDirContents(String remoteBaseDir, boolean recursive) {
        List<FtpItem> out = new ArrayList<FtpItem>();
        if (!isConnected()) return out;
        String base = normalizeFtpPath(remoteBaseDir);
        try {
            fetchDirRecursive(base, "", recursive, out);
        } catch (Exception ignored) {}
        return out;
    }

    private void fetchDirRecursive(String rootDir, String relSub, boolean recursive, List<FtpItem> outList) throws Exception {
        String targetDir = rootDir;
        if (relSub.length() > 0) {
            targetDir = "/".equals(rootDir) ? ("/" + relSub) : (rootDir + "/" + relSub);
        }
        List<FtpItem> level = listSingleDirectory(targetDir, relSub);
        for (int i = 0; i < level.size(); i++) {
            FtpItem item = level.get(i);
            outList.add(item);
            if (item.isDirectory && recursive) {
                fetchDirRecursive(rootDir, item.path, true, outList);
            }
        }
    }

    public synchronized List<FtpItem> listSingleDirectory(String absDirPath, String prefixRel) throws Exception {
        List<FtpItem> list = new ArrayList<FtpItem>();
        if (!isConnected()) return list;

        sendCmd("CWD " + normalizeFtpPath(absDirPath));
        int cwdCode = readReplyCode();
        if (cwdCode != 250 && cwdCode != 200) {
            return list;
        }

        Socket dataSock = openPassiveSocket();
        if (dataSock == null) return list;

        boolean useMlsd = true;
        sendCmd("MLSD");
        int code = readReplyCode();
        if (code != 150 && code != 125) {
            useMlsd = false;
            sendCmd("LIST");
            code = readReplyCode();
            if (code != 150 && code != 125) {
                try { dataSock.close(); } catch (Exception ignored) {}
                return list;
            }
        }

        BufferedReader dataReader = null;
        try {
            dataReader = new BufferedReader(new InputStreamReader(dataSock.getInputStream(), "UTF-8"));
            String line;
            while ((line = dataReader.readLine()) != null) {
                FtpItem item = useMlsd ? parseMlsdLine(line, prefixRel) : parseListLine(line, prefixRel);
                if (item != null && item.name.length() > 0 && !".".equals(item.name) && !"..".equals(item.name)) {
                    list.add(item);
                }
            }
        } finally {
            try { if (dataReader != null) dataReader.close(); } catch (Exception ignored) {}
            try { dataSock.close(); } catch (Exception ignored) {}
        }
        readReplyCode(); // 226 Transfer complete
        return list;
    }

    private FtpItem parseMlsdLine(String line, String prefixRel) {
        if (line == null) return null;
        int space = line.indexOf(' ');
        if (space <= 0) return null;
        String facts = line.substring(0, space);
        String name = line.substring(space + 1).trim();
        if (".".equals(name) || "..".equals(name)) return null;

        FtpItem item = new FtpItem();
        item.name = name;
        item.path = (prefixRel != null && prefixRel.length() > 0) ? (prefixRel + "/" + name) : name;

        String[] tokens = facts.split(";");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase();
            String v = t.substring(eq + 1).trim().toLowerCase();
            if ("type".equals(k)) {
                if ("dir".equals(v)) item.isDirectory = true;
                else if ("cdir".equals(v) || "pdir".equals(v)) return null;
            } else if ("size".equals(k)) {
                try { item.size = Long.parseLong(v); } catch (Exception ignored) {}
            }
        }
        return item;
    }

    private FtpItem parseListLine(String line, String prefixRel) {
        if (line == null) return null;
        line = line.trim();
        if (line.length() == 0 || line.startsWith("total ")) return null;

        FtpItem item = new FtpItem();
        // Windows DOS style: 11-14-26  01:05PM       <DIR>           FolderName
        if (Character.isDigit(line.charAt(0))) {
            StringTokenizer st = new StringTokenizer(line);
            if (st.countTokens() < 4) return null;
            st.nextToken(); // date
            st.nextToken(); // time
            String dirOrSize = st.nextToken();
            if ("<DIR>".equalsIgnoreCase(dirOrSize)) {
                item.isDirectory = true;
            } else {
                try { item.size = Long.parseLong(dirOrSize); } catch (Exception ignored) {}
            }
            String name = st.nextToken("").trim();
            item.name = name;
            item.path = (prefixRel != null && prefixRel.length() > 0) ? (prefixRel + "/" + name) : name;
            return item;
        }

        // Unix style: drwxr-xr-x 2 user group 4096 Nov 14 12:00 filename
        char first = line.charAt(0);
        if (first != 'd' && first != '-' && first != 'l') return null;
        item.isDirectory = (first == 'd');

        StringTokenizer st = new StringTokenizer(line);
        if (st.countTokens() < 9) return null;
        st.nextToken(); // perms
        st.nextToken(); // links
        st.nextToken(); // owner
        st.nextToken(); // group
        String sizeStr = st.nextToken();
        try { item.size = Long.parseLong(sizeStr); } catch (Exception ignored) {}
        st.nextToken(); // month
        st.nextToken(); // day
        st.nextToken(); // time/year
        String name = st.nextToken("").trim();
        int arrow = name.indexOf(" -> ");
        if (arrow > 0) name = name.substring(0, arrow).trim();

        item.name = name;
        item.path = (prefixRel != null && prefixRel.length() > 0) ? (prefixRel + "/" + name) : name;
        return item;
    }

    public synchronized boolean mkdirRecursive(String remoteDir) {
        if (!isConnected()) return false;
        String norm = normalizeFtpPath(remoteDir);
        if ("/".equals(norm)) return true;
        String[] parts = norm.split("/");
        String cur = "";
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            cur = cur + "/" + parts[i];
            try {
                sendCmd("MKD " + cur);
                readReplyCode();
            } catch (Exception ignored) {}
        }
        return true;
    }

    public synchronized boolean uploadFile(File localFile, String remoteFullFilePath) {
        if (!isConnected() || localFile == null || !localFile.exists()) return false;
        String norm = normalizeFtpPath(remoteFullFilePath);
        int lastSlash = norm.lastIndexOf('/');
        if (lastSlash > 0) {
            mkdirRecursive(norm.substring(0, lastSlash));
        }

        Socket dataSock = null;
        InputStream fis = null;
        OutputStream dos = null;
        try {
            dataSock = openPassiveSocket();
            if (dataSock == null) return false;

            sendCmd("STOR " + norm);
            int code = readReplyCode();
            if (code != 150 && code != 125) {
                dataSock.close();
                return false;
            }

            fis = new BufferedInputStream(new FileInputStream(localFile));
            dos = new BufferedOutputStream(dataSock.getOutputStream());
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) {
                dos.write(buf, 0, n);
            }
            dos.flush();
            dos.close();
            dos = null;
            dataSock.close();
            dataSock = null;

            int endCode = readReplyCode();
            return endCode == 226 || endCode == 250;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (dos != null) dos.close(); } catch (Exception ignored) {}
            try { if (dataSock != null) dataSock.close(); } catch (Exception ignored) {}
        }
    }

    public synchronized boolean downloadFile(String remoteFullFilePath, File localDest) {
        if (!isConnected() || localDest == null) return false;
        File parent = localDest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String norm = normalizeFtpPath(remoteFullFilePath);
        Socket dataSock = null;
        InputStream dis = null;
        OutputStream fos = null;
        try {
            dataSock = openPassiveSocket();
            if (dataSock == null) return false;

            sendCmd("RETR " + norm);
            int code = readReplyCode();
            if (code != 150 && code != 125) {
                dataSock.close();
                return false;
            }

            dis = new BufferedInputStream(dataSock.getInputStream());
            fos = new BufferedOutputStream(new FileOutputStream(localDest));
            byte[] buf = new byte[16384];
            int n;
            while ((n = dis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.close();
            fos = null;
            dis.close();
            dis = null;
            dataSock.close();
            dataSock = null;

            int endCode = readReplyCode();
            return endCode == 226 || endCode == 250;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (dis != null) dis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (dataSock != null) dataSock.close(); } catch (Exception ignored) {}
        }
    }

    public synchronized boolean deleteRemote(String remoteFullPath, boolean isDir) {
        if (!isConnected()) return false;
        try {
            String norm = normalizeFtpPath(remoteFullPath);
            sendCmd((isDir ? "RMD " : "DELE ") + norm);
            int code = readReplyCode();
            return code == 250 || code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean copyLocalSubtreeToRemote(File localDir, String remoteBaseDir, boolean recursive) {
        if (localDir == null || !localDir.exists()) return false;
        File[] files = localDir.listFiles();
        if (files == null) return true;
        String base = normalizeFtpPath(remoteBaseDir);
        mkdirRecursive(base);

        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String targetRemote = "/".equals(base) ? ("/" + f.getName()) : (base + "/" + f.getName());
            if (f.isDirectory()) {
                if (recursive) {
                    mkdirRecursive(targetRemote);
                    if (!copyLocalSubtreeToRemote(f, targetRemote, true)) return false;
                }
            } else if (f.isFile()) {
                if (!uploadFile(f, targetRemote)) return false;
            }
        }
        return true;
    }

    public synchronized boolean copySubtreeToLocal(String remoteBaseDir, File localDir, boolean recursive) {
        if (!localDir.exists()) localDir.mkdirs();
        String base = normalizeFtpPath(remoteBaseDir);
        List<FtpItem> items = fetchRemoteDirContents(base, recursive);
        for (int i = 0; i < items.size(); i++) {
            FtpItem item = items.get(i);
            File dest = new File(localDir, item.path.replace('/', File.separatorChar));
            if (item.isDirectory) {
                dest.mkdirs();
            } else {
                String fullRemote = "/".equals(base) ? ("/" + item.path) : (base + "/" + item.path);
                if (!downloadFile(fullRemote, dest)) return false;
            }
        }
        return true;
    }

    private Socket openPassiveSocket() throws Exception {
        sendCmd("PASV");
        String reply = readFullReply();
        if (reply == null || !reply.startsWith("227")) return null;

        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open + 1);
        if (open < 0 || close < 0) return null;

        String[] nums = reply.substring(open + 1, close).split(",");
        if (nums.length < 6) return null;

        String ip = nums[0].trim() + "." + nums[1].trim() + "." + nums[2].trim() + "." + nums[3].trim();
        if ("0.0.0.0".equals(ip) || ip.startsWith("127.")) {
            ip = connectedHost;
        }
        int p1 = Integer.parseInt(nums[4].trim());
        int p2 = Integer.parseInt(nums[5].trim());
        int dataPort = (p1 << 8) + p2;

        Socket data = new Socket();
        data.connect(new InetSocketAddress(ip, dataPort), timeoutMs);
        data.setSoTimeout(timeoutMs);
        return data;
    }

    private void sendCmd(String cmd) throws Exception {
        writer.write((cmd + "\r\n").getBytes("UTF-8"));
        writer.flush();
    }

    private int readReplyCode() throws Exception {
        String r = readFullReply();
        if (r == null || r.length() < 3) return -1;
        try {
            return Integer.parseInt(r.substring(0, 3));
        } catch (Exception e) {
            return -1;
        }
    }

    private String readFullReply() throws Exception {
        String lastLine = null;
        String line;
        while ((line = reader.readLine()) != null) {
            lastLine = line;
            if (line.length() >= 4 && Character.isDigit(line.charAt(0))
                    && Character.isDigit(line.charAt(1))
                    && Character.isDigit(line.charAt(2))
                    && line.charAt(3) == ' ') {
                break;
            }
        }
        return lastLine;
    }
}
