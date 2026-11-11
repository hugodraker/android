package com.example.asyncer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class FtpClient {
    public String user = "anonymous";
    public String pass = "anonymous@";
    public String host = "";
    public int port = 21;
    public String path = "";

    public static FtpClient parseUrl(String url) {
        if (url == null || !url.toLowerCase().startsWith("ftp://")) return null;
        FtpClient f = new FtpClient();
        String temp = url.substring(6);

        int at = temp.indexOf('@');
        if (at >= 0) {
            String userInfo = temp.substring(0, at);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                f.user = userInfo.substring(0, colon);
                f.pass = userInfo.substring(colon + 1);
            } else {
                f.user = userInfo;
                f.pass = "";
            }
            temp = temp.substring(at + 1);
        }

        int slash = temp.indexOf('/');
        if (slash >= 0) {
            f.path = temp.substring(slash + 1);
            temp = temp.substring(0, slash);
        }

        int portColon = temp.indexOf(':');
        if (portColon >= 0) {
            try {
                f.port = Integer.parseInt(temp.substring(portColon + 1));
            } catch (NumberFormatException ignored) {}
            f.host = temp.substring(0, portColon);
        } else {
            f.host = temp;
        }
        return f;
    }

    private static String readReply(BufferedReader in) throws Exception {
        String line;
        String lastLine = "";
        while ((line = in.readLine()) != null) {
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

    private static void sendCmd(OutputStream out, String cmd) throws Exception {
        out.write((cmd + "\r\n").getBytes("US-ASCII"));
        out.flush();
    }

    private static Socket openPasvSocket(BufferedReader in, OutputStream out, String fallbackHost) throws Exception {
        sendCmd(out, "PASV");
        String reply = readReply(in);
        if (!reply.startsWith("227")) return null;
        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open);
        if (open < 0 || close < 0) return null;
        String[] parts = reply.substring(open + 1, close).split(",");
        if (parts.length < 6) return null;
        int p1 = Integer.parseInt(parts[4].trim());
        int p2 = Integer.parseInt(parts[5].trim());
        Socket dataSock = new Socket();
        dataSock.connect(new InetSocketAddress(fallbackHost, (p1 << 8) + p2), 5000);
        dataSock.setSoTimeout(10000);
        return dataSock;
    }

    public boolean downloadFile(File localDst) {
        Socket ctrl = null;
        try {
            ctrl = new Socket();
            ctrl.connect(new InetSocketAddress(host, port), 5000);
            ctrl.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(ctrl.getInputStream()));
            OutputStream out = ctrl.getOutputStream();

            readReply(in);
            sendCmd(out, "USER " + user);
            String r = readReply(in);
            if (r.startsWith("331")) {
                sendCmd(out, "PASS " + pass);
                r = readReply(in);
            }
            if (!r.startsWith("230")) return false;

            sendCmd(out, "TYPE I");
            readReply(in);

            Socket dataSock = openPasvSocket(in, out, host);
            if (dataSock == null) return false;

            sendCmd(out, "RETR " + path);
            r = readReply(in);
            if (!r.startsWith("150") && !r.startsWith("125")) {
                dataSock.close();
                return false;
            }

            File parent = localDst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            InputStream din = dataSock.getInputStream();
            FileOutputStream fos = new FileOutputStream(localDst);
            byte[] buf = new byte[16384];
            int len;
            while ((len = din.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.close();
            dataSock.close();
            readReply(in);
            sendCmd(out, "QUIT");
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (ctrl != null) try { ctrl.close(); } catch (Exception ignored) {}
        }
    }

    public boolean uploadFile(File localSrc) {
        if (!localSrc.exists() || !localSrc.isFile()) return false;
        Socket ctrl = null;
        try {
            ctrl = new Socket();
            ctrl.connect(new InetSocketAddress(host, port), 5000);
            ctrl.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(ctrl.getInputStream()));
            OutputStream out = ctrl.getOutputStream();

            readReply(in);
            sendCmd(out, "USER " + user);
            String r = readReply(in);
            if (r.startsWith("331")) {
                sendCmd(out, "PASS " + pass);
                r = readReply(in);
            }
            if (!r.startsWith("230")) return false;

            sendCmd(out, "TYPE I");
            readReply(in);

            Socket dataSock = openPasvSocket(in, out, host);
            if (dataSock == null) return false;

            sendCmd(out, "STOR " + path);
            r = readReply(in);
            if (!r.startsWith("150") && !r.startsWith("125")) {
                dataSock.close();
                return false;
            }

            OutputStream dout = dataSock.getOutputStream();
            FileInputStream fis = new FileInputStream(localSrc);
            byte[] buf = new byte[16384];
            int len;
            while ((len = fis.read(buf)) > 0) {
                dout.write(buf, 0, len);
            }
            dout.flush();
            fis.close();
            dataSock.close();
            readReply(in);
            sendCmd(out, "QUIT");
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (ctrl != null) try { ctrl.close(); } catch (Exception ignored) {}
        }
    }
}
