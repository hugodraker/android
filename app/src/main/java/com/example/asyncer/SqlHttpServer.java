package com.example.asyncer;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SqlHttpServer {

    public static class SqlQueryResult {
        public boolean ok = false;
        public String error = "";
        public List<String> columns = null;
        public List<List<String>> rows = null;
        public int rowsAffected = 0;
    }

    private static SqlHttpServer instance;

    private final Context appContext;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private int boundPort = -1;
    private SyncRunner.LogCallback logger;

    public static synchronized SqlHttpServer getInstance(Context ctx) {
        if (instance == null) {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            instance = new SqlHttpServer(app);
        }
        return instance;
    }

    private SqlHttpServer(Context appCtx) {
        this.appContext = appCtx;
    }

    public void setLogger(SyncRunner.LogCallback l) {
        this.logger = l;
    }

    private void log(String msg) {
        if (logger != null) logger.onLog(msg);
    }

    public synchronized boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public synchronized int getBoundPort() {
        return boundPort;
    }

    public synchronized void start(int port) {
        if (isRunning() && boundPort == port) return;
        stop();
        final int targetPort = (port > 0 && port <= 65535) ? port : 8080;
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                try {
                    serverSocket = new ServerSocket(targetPort);
                    boundPort = targetPort;
                    log("SQL HTTP Server listening on port " + targetPort);
                    while (running && !serverSocket.isClosed()) {
                        final Socket client = serverSocket.accept();
                        new Thread(new Runnable() {
                            public void run() {
                                handleClient(client);
                            }
                        }).start();
                    }
                } catch (Exception e) {
                    if (running) {
                        log("SQL HTTP Server stopped: " + e.getMessage());
                    }
                } finally {
                    running = false;
                    boundPort = -1;
                }
            }
        }, "AsyncerSqlHttpServer");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        boundPort = -1;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        serverSocket = null;
    }

    private void handleClient(Socket sock) {
        InputStream in = null;
        OutputStream out = null;
        try {
            sock.setSoTimeout(30000);
            in = new BufferedInputStream(sock.getInputStream());
            out = sock.getOutputStream();

            String requestLine = readLineAscii(in);
            if (requestLine == null || requestLine.length() == 0) return;

            String[] reqParts = requestLine.split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0].toUpperCase();
            String rawTarget = reqParts[1];

            String path = rawTarget;
            int qIdx = rawTarget.indexOf('?');
            if (qIdx >= 0) {
                path = rawTarget.substring(0, qIdx);
            }

            Map<String, String> headers = new HashMap<String, String>();
            int contentLength = 0;
            String headerLine;
            while ((headerLine = readLineAscii(in)) != null && headerLine.length() > 0) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String hk = headerLine.substring(0, colon).trim().toLowerCase();
                    String hv = headerLine.substring(colon + 1).trim();
                    headers.put(hk, hv);
                    if ("content-length".equals(hk)) {
                        contentLength = SyncRunner.parseIntSafe(hv, 0);
                    }
                }
            }

            byte[] bodyBytes = new byte[0];
            if (contentLength > 0 && contentLength <= 16 * 1024 * 1024) {
                bodyBytes = new byte[contentLength];
                int readTotal = 0;
                while (readTotal < contentLength) {
                    int n = in.read(bodyBytes, readTotal, contentLength - readTotal);
                    if (n < 0) break;
                    readTotal += n;
                }
            }

            SyncRunner.IniConfig cfg = SyncRunner.loadIniConfig(new File(appContext.getFilesDir(), "asyncer.ini"));
            boolean enabled = "1".equals(cfg.global.get("sql_server_enabled")) || "true".equalsIgnoreCase(cfg.global.get("sql_server_enabled"));
            if (!enabled) {
                sendResponse(out, 503, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"error\":\"SQL HTTP server is disabled in settings\"}");
                return;
            }

            // GET / -> Show login form only (no console access until authenticated via POST)
            if ("GET".equals(method) && "/".equals(path)) {
                sendResponse(out, 200, "text/html; charset=UTF-8", buildLoginHtml(null));
                return;
            }

            if (!"POST".equals(method)) {
                sendResponse(out, 405, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"error\":\"Method not allowed: POST required\"}");
                return;
            }

            // Parse credentials & parameters strictly from POST body (and optional auth headers)
            Map<String, String> postParams = new HashMap<String, String>();
            String contentType = headers.get("content-type");
            String bodyStr = new String(bodyBytes, "UTF-8");
            boolean isRawSqlBody = contentType != null &&
                    (contentType.toLowerCase().contains("application/sql") || contentType.toLowerCase().contains("text/plain"));

            if (!isRawSqlBody) {
                parseUrlEncodedParams(bodyStr, postParams);
            }

            String[] creds = extractCredentials(headers, postParams);
            boolean hasCreds = (creds != null);
            boolean validCreds = hasCreds && verifyCredentials(cfg.global, creds[0], creds[1]);

            if ("/".equals(path)) {
                if (!hasCreds) {
                    sendResponse(out, 401, "text/html; charset=UTF-8", buildLoginHtml(null));
                } else if (!validCreds) {
                    sendResponse(out, 401, "text/html; charset=UTF-8", buildLoginHtml("Invalid username or password."));
                } else {
                    // Authenticated: render console WITHOUT login/password fields
                    sendResponse(out, 200, "text/html; charset=UTF-8", buildAuthenticatedConsoleHtml(creds[0], creds[1]));
                }
                return;
            }

            if (!validCreds) {
                sendResponse(out, 401, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"error\":\"Unauthorized: missing or invalid username/password in POST\"}");
                return;
            }

            if ("/sql".equals(path)) {
                handleSqlPost(out, cfg.global, postParams, isRawSqlBody ? bodyStr : null);
            } else if ("/run_job".equals(path)) {
                handleRunJobPost(out, postParams);
            } else {
                sendResponse(out, 404, "application/json; charset=UTF-8",
                        "{\"ok\":false,\"error\":\"Unknown endpoint: " + escapeJson(path) + "\"}");
            }
        } catch (Exception e) {
            try {
                if (out != null) {
                    sendResponse(out, 500, "application/json; charset=UTF-8",
                            "{\"ok\":false,\"error\":\"" + escapeJson(String.valueOf(e.getMessage())) + "\"}");
                }
            } catch (Exception ignored) {}
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    // Returns [user, pass] only if BOTH user and pass were explicitly supplied in POST or Auth headers
    private String[] extractCredentials(Map<String, String> headers, Map<String, String> postParams) {
        if (postParams.containsKey("user") && postParams.containsKey("pass")) {
            return new String[] { postParams.get("user"), postParams.get("pass") };
        }
        if (headers.containsKey("x-user") && headers.containsKey("x-pass")) {
            return new String[] { headers.get("x-user"), headers.get("x-pass") };
        }
        if (headers.containsKey("authorization")) {
            String auth = headers.get("authorization");
            if (auth != null && auth.toLowerCase().startsWith("basic ")) {
                try {
                    byte[] decoded = decodeBase64Flexible(auth.substring(6).trim());
                    String pair = new String(decoded, "UTF-8");
                    int sep = pair.indexOf(':');
                    if (sep >= 0) {
                        return new String[] { pair.substring(0, sep), pair.substring(sep + 1) };
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean verifyCredentials(Map<String, String> globalCfg, String reqUser, String reqPass) {
        String expectedUser = globalCfg.containsKey("http_user") ? globalCfg.get("http_user") : "admin";
        String expectedPass = globalCfg.containsKey("http_pass") ? globalCfg.get("http_pass") : "";
        if (reqUser == null || reqPass == null) return false;
        if (expectedUser.length() == 0) return false;
        return expectedUser.equals(reqUser) && expectedPass.equals(reqPass);
    }

    private void handleSqlPost(OutputStream out, Map<String, String> globalCfg, Map<String, String> params, String rawBody) throws Exception {
        String sql = null;
        String encoding = params.get("encoding");

        if (params.containsKey("query_b64") && params.get("query_b64").length() > 0) {
            sql = new String(decodeBase64Flexible(params.get("query_b64")), "UTF-8");
        } else if (params.containsKey("query")) {
            String q = params.get("query");
            if ("base64".equalsIgnoreCase(encoding) || "b64".equalsIgnoreCase(encoding)) {
                sql = new String(decodeBase64Flexible(q), "UTF-8");
            } else if ("hex".equalsIgnoreCase(encoding)) {
                sql = new String(decodeHex(q), "UTF-8");
            } else {
                sql = q;
            }
        } else if (rawBody != null && rawBody.length() > 0) {
            sql = rawBody;
        }

        if (sql == null || sql.trim().length() == 0) {
            sendResponse(out, 400, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"error\":\"Missing SQL query (supply query_b64, query, or raw body)\"}");
            return;
        }

        String dbPath = params.get("db");
        if (dbPath == null || dbPath.trim().length() == 0) {
            dbPath = globalCfg.get("sql_db_path");
        }
        if (dbPath == null || dbPath.trim().length() == 0) {
            File ext = Environment.getExternalStorageDirectory();
            dbPath = new File(ext != null ? ext : appContext.getFilesDir(), "asyncer.db").getAbsolutePath();
        }

        log("HTTP SQL [" + new File(dbPath).getName() + "]: " + (sql.length() > 80 ? sql.substring(0, 80) + "..." : sql));
        SqlQueryResult res = executeSqlWithSqlDroid(dbPath.trim(), sql);

        String format = params.get("format");
        if ("csv".equalsIgnoreCase(format)) {
            if (!res.ok) {
                sendResponse(out, 400, "text/plain; charset=UTF-8", "ERROR: " + res.error);
            } else {
                sendResponse(out, 200, "text/csv; charset=UTF-8", formatResultAsCsv(res));
            }
            return;
        }

        if (!res.ok) {
            sendResponse(out, 400, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"error\":\"" + escapeJson(res.error) + "\"}");
            return;
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"db\":\"").append(escapeJson(dbPath)).append("\",");
        if (res.columns != null && res.rows != null) {
            json.append("\"columns\":[");
            for (int c = 0; c < res.columns.size(); c++) {
                if (c > 0) json.append(',');
                json.append('"').append(escapeJson(res.columns.get(c))).append('"');
            }
            json.append("],\"row_count\":").append(res.rows.size()).append(",\"rows\":[");
            for (int r = 0; r < res.rows.size(); r++) {
                if (r > 0) json.append(',');
                json.append('[');
                List<String> row = res.rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    if (c > 0) json.append(',');
                    String val = row.get(c);
                    if (val == null) json.append("null");
                    else json.append('"').append(escapeJson(val)).append('"');
                }
                json.append(']');
            }
            json.append("]}");
        } else {
            json.append("\"rows_affected\":").append(res.rowsAffected).append("}");
        }
        sendResponse(out, 200, "application/json; charset=UTF-8", json.toString());
    }

    private void handleRunJobPost(OutputStream out, Map<String, String> params) throws Exception {
        String jobParam = params.get("job");
        if (jobParam == null || jobParam.trim().length() == 0) {
            jobParam = params.get("profile");
        }
        if (jobParam == null || jobParam.trim().length() == 0) {
            sendResponse(out, 400, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"error\":\"Missing 'job' parameter (1..5 or all)\"}");
            return;
        }

        String jp = jobParam.trim();
        boolean runScript = !"0".equals(params.get("run_script"));
        log("HTTP POST /run_job -> job=" + jp);

        if ("all".equalsIgnoreCase(jp)) {
            StringBuilder resultsJson = new StringBuilder();
            resultsJson.append("{\"ok\":true,\"results\":[");
            for (int i = 0; i < 5; i++) {
                if (i > 0) resultsJson.append(',');
                String r = SyncRunner.runJobAndScript(appContext, null, i, runScript, logger);
                resultsJson.append("{\"job\":").append(i + 1).append(",\"status\":\"").append(escapeJson(r)).append("\"}");
            }
            resultsJson.append("]}");
            sendResponse(out, 200, "application/json; charset=UTF-8", resultsJson.toString());
            return;
        }

        int jobNum = SyncRunner.parseIntSafe(jp, -1);
        int jobIdx = (jobNum >= 1 && jobNum <= 5) ? (jobNum - 1) : jobNum;
        if (jobIdx < 0 || jobIdx >= 5) {
            sendResponse(out, 400, "application/json; charset=UTF-8",
                    "{\"ok\":false,\"error\":\"Invalid job index (use 1..5 or all)\"}");
            return;
        }

        String status = SyncRunner.runJobAndScript(appContext, null, jobIdx, runScript, logger);
        sendResponse(out, 200, "application/json; charset=UTF-8",
                "{\"ok\":true,\"job\":" + (jobIdx + 1) + ",\"status\":\"" + escapeJson(status) + "\"}");
    }

    public static synchronized SqlQueryResult executeSqlWithSqlDroid(String dbPath, String sql) {
        SqlQueryResult res = new SqlQueryResult();
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            File dbFile = new File(dbPath);
            if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
                dbFile.getParentFile().mkdirs();
            }

            Driver driver = (Driver) Class.forName("org.sqldroid.SQLDroidDriver").newInstance();
            DriverManager.registerDriver(driver);
            conn = DriverManager.getConnection("jdbc:sqldroid:" + dbFile.getAbsolutePath());
            stmt = conn.createStatement();

            List<String> statements = splitSqlStatements(sql);
            for (int i = 0; i < statements.size(); i++) {
                String singleSql = statements.get(i).trim();
                if (singleSql.length() == 0) continue;

                boolean isLast = (i == statements.size() - 1);
                boolean hasResultSet = stmt.execute(singleSql);
                if (hasResultSet) {
                    rs = stmt.getResultSet();
                    if (isLast && rs != null) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        res.columns = new ArrayList<String>(colCount);
                        for (int c = 1; c <= colCount; c++) {
                            String label = meta.getColumnLabel(c);
                            res.columns.add(label != null && label.length() > 0 ? label : meta.getColumnName(c));
                        }
                        res.rows = new ArrayList<List<String>>();
                        while (rs.next()) {
                            List<String> row = new ArrayList<String>(colCount);
                            for (int c = 1; c <= colCount; c++) {
                                row.add(rs.getString(c));
                            }
                            res.rows.add(row);
                        }
                    }
                    if (rs != null) {
                        rs.close();
                        rs = null;
                    }
                } else {
                    int upd = stmt.getUpdateCount();
                    if (upd > 0) res.rowsAffected += upd;
                }
            }
            res.ok = true;
        } catch (Throwable t) {
            res.ok = false;
            res.error = t.getMessage() != null ? t.getMessage() : t.toString();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
        return res;
    }

    private static List<String> splitSqlStatements(String sql) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < sql.length()) ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                cur.append(c);
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                cur.append(c);
                if (c == '*' && next == '/') {
                    cur.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    if (next == quote) {
                        cur.append(next);
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                cur.append(c).append(next);
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                cur.append(c).append(next);
                i++;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                cur.append(c);
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String formatResultAsCsv(SqlQueryResult res) {
        if (res.columns == null || res.rows == null) {
            return "rows_affected\r\n" + res.rowsAffected + "\r\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < res.columns.size(); c++) {
            if (c > 0) sb.append(',');
            sb.append(escapeCsv(res.columns.get(c)));
        }
        sb.append("\r\n");
        for (int r = 0; r < res.rows.size(); r++) {
            List<String> row = res.rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(',');
                sb.append(escapeCsv(row.get(c)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String escapeCsv(String v) {
        if (v == null) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static void parseUrlEncodedParams(String raw, Map<String, String> out) {
        if (raw == null || raw.length() == 0) return;
        String[] pairs = raw.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String p = pairs[i];
            if (p.length() == 0) continue;
            int eq = p.indexOf('=');
            try {
                if (eq >= 0) {
                    String k = URLDecoder.decode(p.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(p.substring(eq + 1), "UTF-8");
                    out.put(k, v);
                } else {
                    out.put(URLDecoder.decode(p, "UTF-8"), "");
                }
            } catch (Exception ignored) {}
        }
    }

    public static byte[] decodeBase64Flexible(String input) {
        if (input == null) return new byte[0];
        String s = input.trim().replace('-', '+').replace('_', '/');
        ByteArrayOutputStream out = new ByteArrayOutputStream((s.length() * 3) / 4);
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int val;
            if (c >= 'A' && c <= 'Z') val = c - 'A';
            else if (c >= 'a' && c <= 'z') val = c - 'a' + 26;
            else if (c >= '0' && c <= '9') val = c - '0' + 52;
            else if (c == '+') val = 62;
            else if (c == '/') val = 63;
            else if (c == '=') break;
            else continue;

            buf = (buf << 6) | val;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static byte[] decodeHex(String hex) {
        String s = hex.trim();
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i + 1 < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static String readLineAscii(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("ISO-8859-1");
    }

    private static void sendResponse(OutputStream out, int code, String contentType, String body) throws IOException {
        byte[] data = body.getBytes("UTF-8");
        String statusText = (code == 200) ? "OK" : (code == 400) ? "Bad Request" : (code == 401) ? "Unauthorized" : "Error";
        String hdr = "HTTP/1.1 " + code + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes("ISO-8859-1"));
        out.write(data);
        out.flush();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String buildLoginHtml(String errorMsg) {
        String errBlock = (errorMsg != null && errorMsg.length() > 0)
                ? "<p style='color:#c62828;font-weight:bold'>" + errorMsg + "</p>" : "";
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Asyncer Login</title>"
                + "<style>body{font-family:sans-serif;max-width:420px;margin:40px auto;padding:0 16px}"
                + "input,button{width:100%;margin:8px 0;padding:10px;box-sizing:border-box}</style></head><body>"
                + "<h2>Asyncer Login</h2>"
                + errBlock
                + "<form method='POST' action='/'>"
                + "<label>Username:</label><input name='user' required autofocus>"
                + "<label>Password:</label><input name='pass' type='password'>"
                + "<button type='submit'>Sign In</button>"
                + "</form></body></html>";
    }

    // Rendered ONLY after valid user & pass were POSTed; contains NO login/password boxes
    private static String buildAuthenticatedConsoleHtml(String authUser, String authPass) {
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Asyncer SQL & Sync Server</title>"
                + "<style>body{font-family:sans-serif;max-width:760px;margin:20px auto;padding:0 12px}"
                + "textarea,select,button{width:100%;margin:6px 0;padding:8px;box-sizing:border-box}"
                + "pre{background:#111;color:#0f0;padding:12px;overflow:auto;min-height:120px}</style></head><body>"
                + "<h2>Asyncer SQLDroid & Sync Console</h2>"
                + "<h3>1. Run SQL Query (Base64 UTF-8 Encoded)</h3>"
                + "<textarea id='q' rows='5'>SELECT sqlite_version() AS version;</textarea>"
                + "<button onclick='runSql()'>Execute SQL (POST /sql)</button>"
                + "<h3>2. Run Sync Job & Associated Script</h3>"
                + "<select id='j'><option value='1'>Job 1</option><option value='2'>Job 2</option>"
                + "<option value='3'>Job 3</option><option value='4'>Job 4</option><option value='5'>Job 5</option>"
                + "<option value='all'>All Jobs</option></select>"
                + "<button onclick='runJob()'>Run Sync Job (POST /run_job)</button>"
                + "<pre id='out'>Authenticated.</pre>"
                + "<script>"
                + "const AUTH_U=\"" + escapeJson(authUser) + "\";"
                + "const AUTH_P=\"" + escapeJson(authPass) + "\";"
                + "function b64Utf8(str){const bytes=new TextEncoder().encode(str);let bin='';for(let i=0;i<bytes.length;i++)bin+=String.fromCharCode(bytes[i]);return btoa(bin);}"
                + "async function runSql(){"
                + "const body=new URLSearchParams({user:AUTH_U,pass:AUTH_P,query_b64:b64Utf8(q.value)});"
                + "const r=await fetch('/sql',{method:'POST',body});out.textContent=await r.text();}"
                + "async function runJob(){"
                + "const body=new URLSearchParams({user:AUTH_U,pass:AUTH_P,job:j.value});"
                + "const r=await fetch('/run_job',{method:'POST',body});out.textContent=await r.text();}"
                + "</script></body></html>";
    }
                        }
