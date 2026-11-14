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

    public interface LogListener {
        void onLog(String msg);
    }

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
    private int timeoutMs = 10000;
    private boolean loggedIn = false;
    private String lastReply = "";
    private LogListener logger;
    private Boolean mlsdSupported = null;

    public void setLogListener(LogListener l) {
        this.logger = l;
    }

    private void log(String msg) {
        if (logger != null) logger.onLog(msg);
    }

    public String getLastReply() {
        return lastReply;
    }

    public synchronized boolean connect(String host, int port, String user, String pass) {
        disconnect();
        if (host == null || host.trim().length() == 0) return false;
        this.connectedHost = host.trim();
        this.connectedPort = port > 0 ? port : 21;
        this.mlsdSupported = null;

        String u = (user == null || user.trim().length() == 0 || "guest".equalsIgnoreCase(user.trim()))
                ? "anonymous" : user.trim();
        String p = (pass == null || pass.length() == 0)
                ? ("anonymous".equalsIgnoreCase(u) ? "anonymous@example.com" : "") : pass;

        try {
            ctrlSocket = new Socket();
            ctrlSocket.connect(new InetSocketAddress(connectedHost, connectedPort), timeoutMs);
            ctrlSocket.setSoTimeout(timeoutMs);
            reader = new BufferedReader(new InputStreamReader(ctrlSocket.getInputStream(), "UTF-8"));
            writer = ctrlSocket.getOutputStream();

            int code = readReplyCode();
            if (code != 220) {
                log("FTP banner rejected: " + lastReply);
                disconnect();
                return false;
            }

            sendCmd("USER " + u);
            code = readReplyCode();
            if (code == 331 || code == 332) {
                sendCmd("PASS " + p);
                code = readReplyCode();
            }
            if (code != 230 && code != 202) {
                // If user left "guest" in UI and server rejected anonymous, retry literal "guest"
                if ("anonymous".equalsIgnoreCase(u) && user != null && "guest".equalsIgnoreCase(user.trim())) {
                    sendCmd("USER guest");
                    code = readReplyCode();
                    if (code == 331 || code == 332) {
                        sendCmd("PASS " + (pass != null ? pass : ""));
                        code = readReplyCode();
                    }
                }
            }
            if (code != 230 && code != 202) {
                log("FTP login failed: " + lastReply);
                disconnect();
                return false;
            }

            sendCmd("TYPE I");
            readReplyCode();
            sendCmd("OPTS UTF8 ON");
            readReplyCode();

            loggedIn = true;
            log("FTP authenticated to " + connectedHost + ":" + connectedPort);
            return true;
        } catch (Exception e) {
            log("FTP connect error: " + e.getMessage());
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
        if (path == null) return "/";
        String p = path.replace('\\', '/').trim();
        if (p.length() == 0 || "PUBLIC".equalsIgnoreCase(p)) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public synchronized List<String> probeRootDirectories(String basePath) throws Exception {
        List<String> dirs = new ArrayList<String>();
        String norm = normalizeFtpPath(basePath);
        dirs.add(norm);
        List<FtpItem> items = listSingleDirectory(norm, "");
        for (int i = 0; i < items.size(); i++) {
            FtpItem it = items.get(i);
            if (it.isDirectory) {
                String full = "/".equals(norm) ? ("/" + it.name) : (norm + "/" + it.name);
                if (!dirs.contains(full)) dirs.add(full);
            }
        }
        return dirs;
    }

    public synchronized List<FtpItem> fetchRemoteDirContents(String remoteBaseDir, boolean recursive) {
        List<FtpItem> out = new ArrayList<FtpItem>();
        if (!isConnected()) return out;
        String base = normalizeFtpPath(remoteBaseDir);
        try {
            fetchDirRecursive(base, "", recursive, out);
        } catch (Exception e) {
            log("FTP list error: " + e.getMessage());
        }
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

        String normDir = normalizeFtpPath(absDirPath);
        sendCmd("CWD " + normDir);
        int cwdCode = readReplyCode();
        if (cwdCode != 250 && cwdCode != 200) {
            // Try relative CWD if root slash was rejected
            if (normDir.startsWith("/") && normDir.length() > 1) {
                sendCmd("CWD " + normDir.substring(1));
                cwdCode = readReplyCode();
            }
            if (cwdCode != 250 && cwdCode != 200) {
                log("FTP CWD " + normDir + " failed: " + lastReply);
                return list;
            }
        }

        Socket dataSock = null;
        boolean usedMlsd = false;

        // Try MLSD first only if not already known to be unsupported
        if (mlsdSupported == null || mlsdSupported.booleanValue()) {
            dataSock = openDataSocket();
            if (dataSock != null) {
                sendCmd("MLSD");
                int code = readReplyCode();
                if (code == 150 || code == 125) {
                    mlsdSupported = Boolean.TRUE;
                    usedMlsd = true;
                } else {
                    mlsdSupported = Boolean.FALSE;
                    try { dataSock.close(); } catch (Exception ignored) {}
                    dataSock = null;
                }
            }
        }

        // Fallback to standard LIST with a fresh PASV socket
        if (!usedMlsd) {
            dataSock = openDataSocket();
            if (dataSock == null) {
                log("FTP passive data connection failed for LIST");
                return list;
            }
            sendCmd("LIST");
            int code = readReplyCode();
            if (code != 150 && code != 125) {
                try { dataSock.close(); } catch (Exception ignored) {}
                log("FTP LIST failed: " + lastReply);
                return list;
            }
        }

        BufferedReader dataReader = null;
        try {
            dataReader = new BufferedReader(new InputStreamReader(dataSock.getInputStream(), "UTF-8"));
            String line;
            while ((line = dataReader.readLine()) != null) {
                FtpItem item = usedMlsd ? parseMlsdLine(line, prefixRel) : parseListLine(line, prefixRel);
                if (item != null && item.name.length() > 0 && !".".equals(item.name) && !"..".equals(item.name)) {
                    list.add(item);
                }
            }
        } finally {
            try { if (dataReader != null) dataReader.close(); } catch (Exception ignored) {}
            try { if (dataSock != null) dataSock.close(); } catch (Exception ignored) {}
        }
        readReplyCode(); // Consume 226 Transfer complete
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
        if (Character.isDigit(line.charAt(0))) {
            StringTokenizer st = new StringTokenizer(line);
            if (st.countTokens() < 4) return null;
            st.nextToken();
            st.nextToken();
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

        char first = line.charAt(0);
        if (first != 'd' && first != '-' && first != 'l') return null;
        item.isDirectory = (first == 'd');

        StringTokenizer st = new StringTokenizer(line);
        if (st.countTokens() < 9) return null;
        st.nextToken(); // permissions
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
        String parentDir = "/";
        String fileName = norm;
        int lastSlash = norm.lastIndexOf('/');
        if (lastSlash >= 0) {
            parentDir = lastSlash == 0 ? "/" : norm.substring(0, lastSlash);
            fileName = norm.substring(lastSlash + 1);
        }

        mkdirRecursive(parentDir);

        Socket dataSock = null;
        InputStream fis = null;
        OutputStream dos = null;
        try {
            // CWD into parent directory first so STOR uses simple filename
            sendCmd("CWD " + parentDir);
            int cwdCode = readReplyCode();

            dataSock = openDataSocket();
            if (dataSock == null) {
                log("FTP PASV failed before uploading " + fileName);
                return false;
            }

            String storArg = (cwdCode == 250 || cwdCode == 200) ? fileName : norm;
            sendCmd("STOR " + storArg);
            int code = readReplyCode();
            if (code != 150 && code != 125) {
                log("FTP STOR rejected (" + storArg + "): " + lastReply);
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
            boolean ok = (endCode == 226 || endCode == 250);
            if (!ok) log("FTP STOR finish error: " + lastReply);
            return ok;
        } catch (Exception e) {
            log("FTP upload exception (" + fileName + "): " + e.getMessage());
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
        String parentDir = "/";
        String fileName = norm;
        int lastSlash = norm.lastIndexOf('/');
        if (lastSlash >= 0) {
            parentDir = lastSlash == 0 ? "/" : norm.substring(0, lastSlash);
            fileName = norm.substring(lastSlash + 1);
        }

        Socket dataSock = null;
        InputStream dis = null;
        OutputStream fos = null;
        try {
            sendCmd("CWD " + parentDir);
            int cwdCode = readReplyCode();

            dataSock = openDataSocket();
            if (dataSock == null) {
                log("FTP PASV failed before downloading " + fileName);
                return false;
            }

            String retrArg = (cwdCode == 250 || cwdCode == 200) ? fileName : norm;
            sendCmd("RETR " + retrArg);
            int code = readReplyCode();
            if (code != 150 && code != 125) {
                log("FTP RETR rejected (" + retrArg + "): " + lastReply);
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
            boolean ok = (endCode == 226 || endCode == 250);
            if (!ok) log("FTP RETR finish error: " + lastReply);
            return ok;
        } catch (Exception e) {
            log("FTP download exception (" + fileName + "): " + e.getMessage());
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

    public synchronized int copyLocalSubtreeToRemote(File localDir, String remoteBaseDir, boolean recursive) {
        if (localDir == null || !localDir.exists()) {
            log("Local folder does not exist: " + (localDir != null ? localDir.getAbsolutePath() : "null"));
            return 0;
        }
        File[] files = localDir.listFiles();
        if (files == null) {
            log("Cannot list local folder (check storage permissions): " + localDir.getAbsolutePath());
            return 0;
        }
        String base = normalizeFtpPath(remoteBaseDir);
        mkdirRecursive(base);

        int count = 0;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String targetRemote = "/".equals(base) ? ("/" + f.getName()) : (base + "/" + f.getName());
            if (f.isDirectory()) {
                if (recursive) {
                    mkdirRecursive(targetRemote);
                    count += copyLocalSubtreeToRemote(f, targetRemote, true);
                }
            } else if (f.isFile()) {
                log("FTP Uploading: " + f.getName() + " (" + f.length() + " B)");
                if (uploadFile(f, targetRemote)) {
                    count++;
                }
            }
        }
        return count;
    }

    public synchronized int copySubtreeToLocal(String remoteBaseDir, File localDir, boolean recursive) {
        if (!localDir.exists()) localDir.mkdirs();
        String base = normalizeFtpPath(remoteBaseDir);
        List<FtpItem> items = fetchRemoteDirContents(base, recursive);
        log("FTP found " + items.size() + " remote item(s) in " + base);
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            FtpItem item = items.get(i);
            File dest = new File(localDir, item.path.replace('/', File.separatorChar));
            if (item.isDirectory) {
                dest.mkdirs();
            } else {
                String fullRemote = "/".equals(base) ? ("/" + item.path) : (base + "/" + item.path);
                log("FTP Downloading: " + item.path + " (" + item.size + " B)");
                if (downloadFile(fullRemote, dest)) {
                    count++;
                }
            }
        }
        return count;
    }

    // Tries EPSV first (NAT/IPv6 friendly), then falls back to PASV
    private Socket openDataSocket() throws Exception {
        sendCmd("EPSV");
        String reply = readFullReply();
        if (reply != null && reply.startsWith("229")) {
            int open = reply.indexOf("(|||");
            int close = reply.indexOf("|)", open + 4);
            if (open >= 0 && close > open) {
                int dataPort = Integer.parseInt(reply.substring(open + 4, close).trim());
                Socket data = new Socket();
                data.connect(new InetSocketAddress(connectedHost, dataPort), timeoutMs);
                data.setSoTimeout(timeoutMs);
                return data;
            }
        }

        sendCmd("PASV");
        reply = readFullReply();
        if (reply == null || !reply.startsWith("227")) return null;

        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open + 1);
        if (open < 0 || close < 0) return null;

        String[] nums = reply.substring(open + 1, close).split(",");
        if (nums.length < 6) return null;

        String ip = nums[0].trim() + "." + nums[1].trim() + "." + nums[2].trim() + "." + nums[3].trim();
        if ("0.0.0.0".equals(ip) || ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")) {
            // Use the control socket host IP so NAT/router port-forwards work reliably
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
            this.lastReply = line;
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
