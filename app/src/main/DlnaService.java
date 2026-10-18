package com.example.dlnaserver;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DlnaService extends Service {

    public static final String SSDP_ADDR = "239.255.255.250"; //[cite: 1]
    public static final int SSDP_PORT = 1900; //[cite: 1]
    public static final int DEFAULT_DLNA_PORT = 8200; //[cite: 1]
    public static final String SERVER_UUID = "uuid:4d696e69-444c-164e-9d41-57696e333200"; //[cite: 1]

    private ServerSocket httpSocket;
    private MulticastSocket ssdpSocket;
    private Thread httpThread;
    private Thread ssdpThread;
    private boolean isRunning = false;
    private int currentPort = DEFAULT_DLNA_PORT;
    private int systemUpdateId = 1; //[cite: 1]
    
    private WifiManager.MulticastLock multicastLock;
    private String baseSharePath;

    @Override
    public void onCreate() {
        super.onCreate();
        // Default share mapped to Android's external storage (SD card)
        File extDir = Environment.getExternalStorageDirectory();
        baseSharePath = extDir != null ? extDir.getAbsolutePath() : "/sdcard";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            currentPort = intent.getIntExtra("PORT", DEFAULT_DLNA_PORT);
        }
        
        WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("DlnaSsdpLock");
        multicastLock.acquire();

        isRunning = true;
        startSsdpServer();
        startHttpServer();
        
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        try {
            if (httpSocket != null) httpSocket.close();
            if (ssdpSocket != null) ssdpSocket.close();
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    private void startSsdpServer() {
        ssdpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ssdpSocket = new MulticastSocket(SSDP_PORT);
                    InetAddress group = InetAddress.getByName(SSDP_ADDR);
                    ssdpSocket.joinGroup(group);

                    byte[] buffer = new byte[2048];
                    while (isRunning) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        ssdpSocket.receive(packet);
                        String request = new String(packet.getData(), 0, packet.getLength());
                        
                        if (request.startsWith("M-SEARCH")) { //[cite: 1]
                            String st = "upnp:rootdevice";
                            if (request.contains("urn:schemas-upnp-org:device:MediaServer:1")) st = "urn:schemas-upnp-org:device:MediaServer:1"; //[cite: 1]
                            
                            String reply = "HTTP/1.1 200 OK\r\n" +
                                    "CACHE-CONTROL: max-age=1800\r\n" +
                                    "EXT:\r\n" +
                                    "LOCATION: http://" + getLocalIpAddress() + ":" + currentPort + "/rootDesc.xml\r\n" +
                                    "SERVER: Android/2.3 UPnP/1.0 DalvikDLNA/1.0\r\n" +
                                    "ST: " + st + "\r\n" +
                                    "USN: " + SERVER_UUID + "::" + st + "\r\n\r\n"; //[cite: 1]
                            
                            byte[] replyBytes = reply.getBytes();
                            DatagramPacket replyPacket = new DatagramPacket(
                                    replyBytes, replyBytes.length, packet.getAddress(), packet.getPort());
                            ssdpSocket.send(replyPacket);
                        }
                    }
                } catch (Exception e) {
                    Log.e("DLNA", "SSDP Error", e);
                }
            }
        });
        ssdpThread.start();
    }

    private void startHttpServer() {
        httpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    httpSocket = new ServerSocket(currentPort);
                    while (isRunning) {
                        Socket client = httpSocket.accept();
                        new Thread(new HttpClientHandler(client)).start();
                    }
                } catch (IOException e) {
                    Log.e("DLNA", "HTTP Server Error", e);
                }
            }
        });
        httpThread.start();
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            return String.format("%d.%d.%d.%d",
                    (ipAddress & 0xff), (ipAddress >> 8 & 0xff),
                    (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // Object ID Hex Encoding/Decoding (Mirrors C implementation)
    // -------------------------------------------------------------------------
    private String bytesToHex(String in) { //[cite: 1]
        StringBuilder out = new StringBuilder();
        for (byte b : in.getBytes()) {
            out.append(String.format("%02X", b));
        }
        return out.toString();
    }

    private String hexToBytes(String hex) { //[cite: 1]
        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return new String(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    private String escapeXml(String raw) { //[cite: 1]
        return raw.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }

    // -------------------------------------------------------------------------
    // Media Profiles Mapping (Mirrors get_media_profile)
    // -------------------------------------------------------------------------
    private static class MediaProfile {
        String mime, upnpClass, dlnaPn;
        MediaProfile(String m, String c, String p) { mime = m; upnpClass = c; dlnaPn = p; }
    }

    private MediaProfile getMediaProfile(String filename) { //[cite: 1]
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) ext = filename.substring(dot + 1).toLowerCase();

        if (ext.equals("mp4") || ext.equals("m4v") || ext.equals("mov")) return new MediaProfile("video/mp4", "object.item.videoItem", "DLNA.ORG_PN=AVC_MP4_MP_SD_AAC_MULT5;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"); //[cite: 1]
        if (ext.equals("mkv") || ext.equals("webm")) return new MediaProfile("video/x-matroska", "object.item.videoItem", "DLNA.ORG_PN=MATROSKA;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"); //[cite: 1]
        if (ext.equals("avi")) return new MediaProfile("video/x-msvideo", "object.item.videoItem", "DLNA.ORG_PN=AVI;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"); //[cite: 1]
        if (ext.equals("mp3")) return new MediaProfile("audio/mpeg", "object.item.audioItem.musicTrack", "DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"); //[cite: 1]
        if (ext.equals("jpg") || ext.equals("jpeg")) return new MediaProfile("image/jpeg", "object.item.imageItem.photo", "DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00f00000000000000000000000000000"); //[cite: 1]
        if (ext.equals("png")) return new MediaProfile("image/png", "object.item.imageItem.photo", "DLNA.ORG_PN=PNG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00f00000000000000000000000000000"); //[cite: 1]
        return null;
    }

    // -------------------------------------------------------------------------
    // HTTP Client Handler
    // -------------------------------------------------------------------------
    private class HttpClientHandler implements Runnable {
        private Socket client;

        public HttpClientHandler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                InputStream is = client.getInputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(is));
                OutputStream out = client.getOutputStream();
                
                String requestLine = in.readLine();
                if (requestLine == null) return;
                
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String path = parts[1];

                // Parse Headers
                int contentLength = 0;
                String rangeHeader = null;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(lower.substring(15).trim());
                    } else if (lower.startsWith("range:")) {
                        rangeHeader = line.substring(6).trim(); //[cite: 1]
                    }
                }

                // Parse Body for POST
                String reqBody = "";
                if (contentLength > 0) {
                    char[] bodyChars = new char[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int r = in.read(bodyChars, read, contentLength - read);
                        if (r == -1) break;
                        read += r;
                    }
                    reqBody = new String(bodyChars);
                }

                if (method.equals("GET") && path.equals("/rootDesc.xml")) { //[cite: 1]
                    sendHttpXml(out, getRootDescXml());
                } else if (method.equals("GET") && path.equals("/cds_scpd.xml")) { //[cite: 1]
                    sendHttpXml(out, getCdsScpdXml());
                } else if (method.equals("GET") && path.equals("/cms_scpd.xml")) { //[cite: 1]
                    sendHttpXml(out, getCmsScpdXml());
                } else if (method.equals("POST") && path.equals("/ctl/ContentDir")) { //[cite: 1]
                    if (reqBody.contains("GetSystemUpdateID")) { //[cite: 1]
                        sendHttpXml(out, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n  <s:Body><u:GetSystemUpdateIDResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><Id>" + systemUpdateId + "</Id></u:GetSystemUpdateIDResponse></s:Body>\r\n</s:Envelope>\r\n"); //[cite: 1]
                    } else if (reqBody.contains("GetSearchCapabilities")) { //[cite: 1]
                        sendHttpXml(out, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n  <s:Body><u:GetSearchCapabilitiesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><SearchCaps></SearchCaps></u:GetSearchCapabilitiesResponse></s:Body>\r\n</s:Envelope>\r\n"); //[cite: 1]
                    } else if (reqBody.contains("GetSortCapabilities")) { //[cite: 1]
                        sendHttpXml(out, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n  <s:Body><u:GetSortCapabilitiesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><SortCaps>dc:title</SortCaps></u:GetSortCapabilitiesResponse></s:Body>\r\n</s:Envelope>\r\n"); //[cite: 1]
                    } else {
                        sendHttpXml(out, buildSoapBrowseResponse(reqBody));
                    }
                } else if (method.equals("POST") && path.equals("/ctl/ConnMgr")) { //[cite: 1]
                    sendHttpXml(out, "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n  <s:Body><u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\"><Source>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/x-msvideo:*,http-get:*:audio/mpeg:*,http-get:*:image/jpeg:*,http-get:*:image/png:*</Source><Sink></Sink></u:GetProtocolInfoResponse></s:Body>\r\n</s:Envelope>\r\n"); //[cite: 1]
                } else if (path.startsWith("/media/")) { //[cite: 1]
                    streamMediaFile(out, path, rangeHeader, method.equals("HEAD"));
                } else {
                    String r404 = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"; //[cite: 1]
                    out.write(r404.getBytes());
                }
                out.flush();
                client.close();
            } catch (Exception e) {
                Log.e("DLNA", "Client Handler Error", e);
            }
        }

        private void sendHttpXml(OutputStream out, String xml) throws IOException {
            String hdr = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                         "Content-Length: " + xml.getBytes("UTF-8").length + "\r\n" +
                         "Server: Android/2.3 UPnP/1.0 DalvikDLNA/1.0\r\n" +
                         "EXT:\r\n" +
                         "Connection: close\r\n\r\n"; //[cite: 1]
            out.write(hdr.getBytes());
            out.write(xml.getBytes("UTF-8"));
        }

        private String extractXmlTag(String xml, String tag) { //[cite: 1]
            Matcher m = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">").matcher(xml);
            return m.find() ? m.group(1) : "0";
        }

        // ---------------------------------------------------------------------
        // Browse Engine
        // ---------------------------------------------------------------------
        private String buildSoapBrowseResponse(String reqXml) {
            String objId = extractXmlTag(reqXml, "ObjectID"); //[cite: 1]
            String browseFlag = extractXmlTag(reqXml, "BrowseFlag"); //[cite: 1]
            int startIdx = Integer.parseInt(extractXmlTag(reqXml, "StartingIndex")); //[cite: 1]
            int reqCount = Integer.parseInt(extractXmlTag(reqXml, "RequestedCount")); //[cite: 1]
            boolean isMetadata = browseFlag.contains("BrowseMetadata"); //[cite: 1]

            StringBuilder didl = new StringBuilder();
            int totalMatches = 0, numReturned = 0;

            File targetDir = null;
            String parentId = "0";

            if (objId.equals("0")) { //[cite: 1]
                targetDir = new File(baseSharePath);
                parentId = "-1";
            } else if (objId.startsWith("S0")) { //[cite: 1]
                if (objId.equals("S0")) { //[cite: 1]
                    targetDir = new File(baseSharePath);
                    parentId = "0";
                } else {
                    String hexRel = objId.substring(3); // skip "S0_"
                    String relPath = hexToBytes(hexRel);
                    targetDir = new File(baseSharePath, relPath);
                    int lastSep = relPath.lastIndexOf('/');
                    parentId = (lastSep > 0) ? "S0_" + bytesToHex(relPath.substring(0, lastSep)) : "S0"; //[cite: 1]
                }
            }

            if (targetDir != null && targetDir.exists()) {
                if (isMetadata) { //[cite: 1]
                    if (targetDir.isDirectory()) {
                        didl.append("&lt;container id=\"").append(objId).append("\" parentID=\"").append(parentId).append("\" restricted=\"1\" searchable=\"0\"&gt;")
                            .append("&lt;dc:title&gt;").append(escapeXml(escapeXml(targetDir.getName().isEmpty() ? "SD Card" : targetDir.getName()))).append("&lt;/dc:title&gt;") //[cite: 1]
                            .append("&lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;&lt;/container&gt;"); //[cite: 1]
                        totalMatches = numReturned = 1;
                    }
                } else {
                    if (objId.equals("0")) { //[cite: 1]
                        // Expose S0 as the root share container
                        didl.append("&lt;container id=\"S0\" parentID=\"0\" restricted=\"1\" searchable=\"0\"&gt;") //[cite: 1]
                            .append("&lt;dc:title&gt;SD Card&lt;/dc:title&gt;")
                            .append("&lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;&lt;/container&gt;"); //[cite: 1]
                        totalMatches = numReturned = 1;
                    } else if (targetDir.isDirectory()) {
                        File[] files = targetDir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.isHidden()) continue; //[cite: 1]
                                MediaProfile profile = f.isDirectory() ? null : getMediaProfile(f.getName()); //[cite: 1]
                                if (!f.isDirectory() && profile == null) continue; //[cite: 1]

                                if (totalMatches >= startIdx && (reqCount == 0 || numReturned < reqCount)) { //[cite: 1]
                                    String childRel = f.getAbsolutePath().substring(baseSharePath.length());
                                    if (childRel.startsWith("/")) childRel = childRel.substring(1);
                                    String childId = "S0_" + bytesToHex(childRel); //[cite: 1]

                                    if (f.isDirectory()) {
                                        didl.append("&lt;container id=\"").append(childId).append("\" parentID=\"").append(objId).append("\" restricted=\"1\" searchable=\"0\"&gt;") //[cite: 1]
                                            .append("&lt;dc:title&gt;").append(escapeXml(escapeXml(f.getName()))).append("&lt;/dc:title&gt;") //[cite: 1]
                                            .append("&lt;upnp:class&gt;object.container.storageFolder&lt;/upnp:class&gt;&lt;/container&gt;"); //[cite: 1]
                                    } else {
                                        String ext = f.getName().substring(f.getName().lastIndexOf('.'));
                                        didl.append("&lt;item id=\"").append(childId).append("\" parentID=\"").append(objId).append("\" restricted=\"1\"&gt;") //[cite: 1]
                                            .append("&lt;dc:title&gt;").append(escapeXml(escapeXml(f.getName()))).append("&lt;/dc:title&gt;") //[cite: 1]
                                            .append("&lt;upnp:class&gt;").append(profile.upnpClass).append("&lt;/upnp:class&gt;") //[cite: 1]
                                            .append("&lt;res size=\"").append(f.length()).append("\" protocolInfo=\"http-get:*:") //[cite: 1]
                                            .append(profile.mime).append(":").append(profile.dlnaPn).append("\"&gt;") //[cite: 1]
                                            .append("http://").append(getLocalIpAddress()).append(":").append(currentPort).append("/media/").append(childId).append("/stream").append(ext) //[cite: 1]
                                            .append("&lt;/res&gt;&lt;/item&gt;"); //[cite: 1]
                                    }
                                    numReturned++;
                                }
                                totalMatches++;
                            }
                        }
                    }
                }
            }

            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                   "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n" +
                   "  <s:Body>\r\n" +
                   "    <u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">\r\n" +
                   "      <Result>&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;" + //[cite: 1]
                   didl.toString() +
                   "&lt;/DIDL-Lite&gt;</Result>\r\n" +
                   "      <NumberReturned>" + numReturned + "</NumberReturned>\r\n" +
                   "      <TotalMatches>" + totalMatches + "</TotalMatches>\r\n" +
                   "      <UpdateID>" + systemUpdateId + "</UpdateID>\r\n" +
                   "    </u:BrowseResponse>\r\n" +
                   "  </s:Body>\r\n" +
                   "</s:Envelope>\r\n"; //[cite: 1]
        }

        // ---------------------------------------------------------------------
        // Byte-Range Media Streaming
        // ---------------------------------------------------------------------
        private void streamMediaFile(OutputStream out, String path, String rangeHeader, boolean isHead) throws IOException {
            // URI format: /media/<ObjectID>/stream.ext[cite: 1]
            String[] segments = path.split("/");
            if (segments.length < 3) return;
            String objId = segments[2];

            File targetFile = null;
            if (objId.startsWith("S0_")) { //[cite: 1]
                String relPath = hexToBytes(objId.substring(3));
                targetFile = new File(baseSharePath, relPath);
            }

            if (targetFile == null || !targetFile.exists() || targetFile.isDirectory()) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes()); //[cite: 1]
                return;
            }

            MediaProfile profile = getMediaProfile(targetFile.getName());
            String mime = (profile != null) ? profile.mime : "application/octet-stream"; //[cite: 1]
            String dlnaPn = (profile != null) ? profile.dlnaPn : "";

            long totalSize = targetFile.length(); //[cite: 1]
            long startByte = 0;
            long endByte = totalSize - 1; //[cite: 1]
            boolean isPartial = false;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) { //[cite: 1]
                isPartial = true;
                String[] range = rangeHeader.substring(6).split("-"); //[cite: 1]
                try {
                    if (range.length > 0 && !range[0].isEmpty()) startByte = Long.parseLong(range[0]); //[cite: 1]
                    if (range.length > 1 && !range[1].isEmpty()) endByte = Long.parseLong(range[1]); //[cite: 1]
                } catch (NumberFormatException e) { /* Ignore and use defaults */ }
            }
            if (startByte >= totalSize) startByte = 0; //[cite: 1]
            if (endByte >= totalSize) endByte = totalSize - 1; //[cite: 1]
            long contentLength = endByte - startByte + 1; //[cite: 1]

            StringBuilder hdr = new StringBuilder();
            if (isPartial) {
                hdr.append("HTTP/1.1 206 Partial Content\r\n") //[cite: 1]
                   .append("Content-Range: bytes ").append(startByte).append("-").append(endByte).append("/").append(totalSize).append("\r\n"); //[cite: 1]
            } else {
                hdr.append("HTTP/1.1 200 OK\r\n"); //[cite: 1]
            }

            hdr.append("Content-Type: ").append(mime).append("\r\n") //[cite: 1]
               .append("Content-Length: ").append(contentLength).append("\r\n") //[cite: 1]
               .append("Accept-Ranges: bytes\r\n") //[cite: 1]
               .append("transferMode.dlna.org: Streaming\r\n") //[cite: 1]
               .append("contentFeatures.dlna.org: ").append(dlnaPn).append("\r\n") //[cite: 1]
               .append("Server: Android/2.3 UPnP/1.0 DalvikDLNA/1.0\r\n")
               .append("Connection: close\r\n\r\n"); //[cite: 1]

            out.write(hdr.toString().getBytes());

            if (!isHead && contentLength > 0) { //[cite: 1]
                RandomAccessFile raf = null;
                try {
                    raf = new RandomAccessFile(targetFile, "r");
                    raf.seek(startByte); //[cite: 1]
                    byte[] chunk = new byte[65536]; //[cite: 1]
                    long rem = contentLength;
                    while (rem > 0 && isRunning) { //[cite: 1]
                        int toRead = (int) Math.min(rem, chunk.length); //[cite: 1]
                        int n = raf.read(chunk, 0, toRead);
                        if (n == -1) break; //[cite: 1]
                        out.write(chunk, 0, n);
                        rem -= n; //[cite: 1]
                    }
                } finally {
                    if (raf != null) raf.close(); //[cite: 1]
                }
            }
        }

        // ---------------------------------------------------------------------
        // Static XML Descriptors
        // ---------------------------------------------------------------------
        private String getRootDescXml() {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                   "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">\r\n" +
                   "  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n" +
                   "  <device>\r\n" +
                   "    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>\r\n" +
                   "    <friendlyName>Android 2.3 DLNA Server</friendlyName>\r\n" +
                   "    <manufacturer>Custom Android Systems</manufacturer>\r\n" +
                   "    <modelName>Android-DLNA-Server</modelName>\r\n" +
                   "    <modelNumber>1.0</modelNumber>\r\n" +
                   "    <UDN>" + SERVER_UUID + "</UDN>\r\n" +
                   "    <dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</dlna:X_DLNADOC>\r\n" +
                   "    <serviceList>\r\n" +
                   "      <service>\r\n" +
                   "        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>\r\n" +
                   "        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>\r\n" +
                   "        <SCPDURL>/cds_scpd.xml</SCPDURL>\r\n" +
                   "        <controlURL>/ctl/ContentDir</controlURL>\r\n" +
                   "        <eventSubURL>/evt/ContentDir</eventSubURL>\r\n" +
                   "      </service>\r\n" +
                   "      <service>\r\n" +
                   "        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>\r\n" +
                   "        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>\r\n" +
                   "        <SCPDURL>/cms_scpd.xml</SCPDURL>\r\n" +
                   "        <controlURL>/ctl/ConnMgr</controlURL>\r\n" +
                   "        <eventSubURL>/evt/ConnMgr</eventSubURL>\r\n" +
                   "      </service>\r\n" +
                   "    </serviceList>\r\n" +
                   "  </device>\r\n" +
                   "</root>\r\n"; //[cite: 1]
        }

        private String getCdsScpdXml() {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                   "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\r\n" +
                   "  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n" +
                   "  <actionList>\r\n" +
                   "    <action><name>Browse</name><argumentList>\r\n" +
                   "      <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>\r\n" +
                   "    </argumentList></action>\r\n" +
                   "    <action><name>GetSystemUpdateID</name><argumentList><argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument></argumentList></action>\r\n" +
                   "    <action><name>GetSearchCapabilities</name><argumentList><argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument></argumentList></action>\r\n" +
                   "    <action><name>GetSortCapabilities</name><argumentList><argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument></argumentList></action>\r\n" +
                   "  </actionList>\r\n" +
                   "  <serviceStateTable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"yes\"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"no\"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>\r\n" +
                   "  </serviceStateTable>\r\n" +
                   "</scpd>\r\n"; //[cite: 1]
        }

        private String getCmsScpdXml() {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
                   "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\r\n" +
                   "  <specVersion><major>1</major><minor>0</minor></specVersion>\r\n" +
                   "  <actionList>\r\n" +
                   "    <action><name>GetProtocolInfo</name><argumentList>\r\n" +
                   "      <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>\r\n" +
                   "      <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>\r\n" +
                   "    </argumentList></action>\r\n" +
                   "    <action><name>GetCurrentConnectionIDs</name><argumentList>\r\n" +
                   "      <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>\r\n" +
                   "    </argumentList></action>\r\n" +
                   "  </actionList>\r\n" +
                   "  <serviceStateTable>\r\n" +
                   "    <stateVariable sendEvents=\"yes\"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>\r\n" +
                   "    <stateVariable sendEvents=\"yes\"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>\r\n" +
                   "  </serviceStateTable>\r\n" +
                   "</scpd>\r\n"; //[cite: 1]
        }
    }
}