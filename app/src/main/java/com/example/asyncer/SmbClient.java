package com.example.asyncer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SmbClientModule {

    public static final int REQ_PICK_LUA = 9001;

    // UI Controls
    public EditText etHost, etPort, etUser, etPass, etExternalCmd;
    public Spinner spShares;
    public Button btnTestShares, btnBrowseLua, btnRunCmd;
    public TextView tvStatus;

    private final Activity activity;
    private final File iniFile;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<String> shareAdapter;
    private final List<String> shareList = new ArrayList<String>();

    public SmbClientModule(Activity act, File ini) {
        this.activity = act;
        this.iniFile = ini;
        disableStrictModeIfPresent();
    }

    // Allows socket operations without crashing on Android 1.6 or throwing NetworkOnMainThreadException on 3.0+
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
            // Android 1.6 - 2.2: StrictMode does not exist, safe to ignore
        }
    }

    /**
     * Builds and returns a LinearLayout containing the SMB Client controls, Share Dropdown,
     * Test/Probe button, and External Command / LuaJ browser.
     */
    public View buildSmbPanel() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        etHost = addLabeledInput(root, "SMB Server IP / Host:", "192.168.1.100", false);
        etPort = addLabeledInput(root, "SMB Port:", "445", false);
        etUser = addLabeledInput(root, "Username (blank for guest):", "guest", false);
        etPass = addLabeledInput(root, "Password:", "", true);

        // Share Dropdown + Test Button Row
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

        // External Command / LuaJ Script Row
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
            et.setInputType(0x00000081); // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD (API 3+)
        }
        parent.addView(et);
        return et;
    }

    // =========================================================================
    // INI PERSISTENCE (Saves share list + active share + credentials + command)
    // =========================================================================

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

                    if ("host".equalsIgnoreCase(k)) etHost.setText(v);
                    else if ("port".equalsIgnoreCase(k)) etPort.setText(v);
                    else if ("user".equalsIgnoreCase(k)) etUser.setText(v);
                    else if ("pass".equalsIgnoreCase(k)) etPass.setText(v);
                    else if ("share".equalsIgnoreCase(k)) selectedShare = v;
                    else if ("shares".equalsIgnoreCase(k)) {
                        String[] parts = splitPipe(v);
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i].length() > 0 && !shareList.contains(parts[i])) {
                                shareList.add(parts[i]);
                            }
                        }
                    } else if ("ext_cmd".equalsIgnoreCase(k)) {
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
        shareAdapter.notifyDataSetChanged();

        if (selectedShare.length() > 0) {
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

            StringBuilder out = new StringBuilder();
            out.append("[SMB]\r\n");
            out.append("host=").append(etHost.getText().toString().trim()).append("\r\n");
            out.append("port=").append(etPort.getText().toString().trim()).append("\r\n");
            out.append("user=").append(etUser.getText().toString().trim()).append("\r\n");
            out.append("pass=").append(etPass.getText().toString()).append("\r\n");
            out.append("share=").append(selShare).append("\r\n");
            out.append("shares=").append(sbShares.toString()).append("\r\n");
            out.append("ext_cmd=").append(etExternalCmd.getText().toString().trim()).append("\r\n");

            fos = new FileOutputStream(iniFile, false);
            fos.write(out.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    public String getSelectedShare() {
        Object item = spShares.getSelectedItem();
        return item != null ? item.toString().trim() : "";
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

    // =========================================================================
    // TEST / PROBE SHARES BUTTON LOGIC
    // =========================================================================

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
                SmbClientSession client = new SmbClientSession(host, port, user, pass);
                try {
                    final List<String> discovered = client.probeShares();
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
                            // Remove placeholder if real shares arrived
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
                            setStatus("Connected (" + (client.isSmb2 ? "SMB2" : "SMB1") + "). Found "
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
                    client.close();
                }
            }
        }).start();
    }

    private void setStatus(final String msg) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvStatus.setText(msg);
        } else {
            uiHandler.post(new Runnable() {
                public void run() {
                    tvStatus.setText(msg);
                }
            });
        }
    }

    // =========================================================================
    // BROWSE & RUN LUAJ SCRIPT / EXTERNAL COMMAND (Android 1.6 - 14+)
    // =========================================================================

    private File getDefaultStartDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists() && ext.canRead()) return ext;
        } catch (Throwable ignored) {}
        return new File("/");
    }

    public void showLuaFileBrowser(final File dir) {
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
            // Sort directories first, then .lua and executable files
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
                    etExternalCmd.setText(target.getAbsolutePath());
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
            if (uri != null) {
                String path = uri.getPath();
                etExternalCmd.setText(path != null ? path : uri.toString());
                saveIni();
            }
        }
    }

    public void executeCommandOrLua(final String cmdOrPath) {
        if (cmdOrPath.length() == 0) {
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
                        String out = new String(baos.toByteArray(), "UTF-8").trim();
                        setStatus("Exit " + code + (out.length() > 0 ? ": " + out : ""));
                    }
                } catch (Exception e) {
                    setStatus("Execution error: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * Executes a .lua file via LuaJ (org.luaj.vm2.lib.jse.JsePlatform).
     * Uses reflection so the module compiles even before the luaj-jse jar is added,
     * and stays 100% compatible with Android 1.6 Dalvik bytecode.
     */
    private String runLuaJScript(String scriptPath) throws Exception {
        Class<?> jsePlatform = Class.forName("org.luaj.vm2.lib.jse.JsePlatform");
        Method standardGlobals = jsePlatform.getMethod("standardGlobals");
        Object globals = standardGlobals.invoke(null);

        Class<?> luaValueClass = Class.forName("org.luaj.vm2.LuaValue");
        // Expose current SMB config into Lua globals
        Method setMethod = luaValueClass.getMethod("set", String.class, String.class);
        setMethod.invoke(globals, "SMB_HOST", etHost.getText().toString().trim());
        setMethod.invoke(globals, "SMB_SHARE", getSelectedShare());
        setMethod.invoke(globals, "SMB_USER", etUser.getText().toString().trim());

        Method loadFile = globals.getClass().getMethod("loadfile", String.class);
        Object chunk = loadFile.invoke(globals, scriptPath);
        Method call = luaValueClass.getMethod("call");
        Object result = call.invoke(chunk);
        return result != null ? result.toString() : "OK";
    }

    // =========================================================================
    // LOW-LEVEL SMB1 / SMB2 CLIENT ENGINE (Android 1.6 Compatible)
    // =========================================================================

    public static class SmbClientSession {
        private final String host;
        private final int port;
        private final String user;
        private final String pass;

        private Socket socket;
        private DataInputStream in;
        private OutputStream out;

        public boolean isSmb2 = false;
        private long smb2SessionId = 0;
        private int smb2TreeId = 0;
        private long smb2MsgId = 0;

        private int smb1Uid = 0;
        private int smb1Tid = 0;
        private int smb1Mid = 1;

        public SmbClientSession(String host, int port, String user, String pass) {
            this.host = host;
            this.port = port;
            this.user = user != null ? user : "";
            this.pass = pass != null ? pass : "";
        }

        public void connectAndAuthenticate() throws Exception {
            // Try SMB2 first
            try {
                openSocket();
                if (negotiateSmb2()) {
                    isSmb2 = true;
                    authenticateSmb2();
                    return;
                }
            } catch (Exception ignored) {
                close();
            }

            // Fallback to SMB1 (NT LM 0.12)
            openSocket();
            isSmb2 = false;
            if (!negotiateSmb1()) {
                throw new Exception("Server rejected both SMB2 and SMB1 negotiate");
            }
            authenticateSmb1();
        }

        private void openSocket() throws Exception {
            close();
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        public void close() {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            in = null;
            out = null;
            socket = null;
        }

        /**
         * Connects to IPC$, opens \pipe\srvsvc, issues DCERPC NetShareEnumAll (Opnum 15, Level 1),
         * and parses all returned share names.
         */
        public List<String> probeShares() throws Exception {
            connectAndAuthenticate();
            List<String> shares;
            if (isSmb2) {
                shares = enumSharesSmb2();
            } else {
                shares = enumSharesSmb1();
            }
            return shares;
        }

        // ---------------------------------------------------------------------
        // SMB2 IMPLEMENTATION
        // ---------------------------------------------------------------------

        private boolean negotiateSmb2() throws Exception {
            smb2MsgId = 0;
            byte[] body = new byte[36 + 4];
            putU16LE(body, 0, 36); // StructureSize
            putU16LE(body, 2, 2);  // DialectCount = 2 (0x0202, 0x0210)
            putU16LE(body, 4, 1);  // SecurityMode = Signing Enabled
            putU16LE(body, 36, 0x0202);
            putU16LE(body, 38, 0x0210);

            byte[] pkt = buildSmb2Packet(0x0000, body); // SMB2_NEGOTIATE
            sendNetBios(pkt);
            byte[] resp = recvNetBios();
            if (resp.length < 64 || resp[0] != (byte) 0xFE || resp[1] != 'S') return false;
            int status = getU32LE(resp, 8);
            return status == 0;
        }

        private void authenticateSmb2() throws Exception {
            // Step 1: NTLMSSP_NEGOTIATE
            byte[] ntlmNeg = buildNtlmsspNegotiate();
            byte[] setup1 = wrapSmb2SessionSetup(ntlmNeg);
            sendNetBios(buildSmb2Packet(0x0001, setup1));
            byte[] resp1 = recvNetBios();

            int status1 = getU32LE(resp1, 8);
            smb2SessionId = getU64LE(resp1, 40);
            if (status1 == 0) return; // Anonymous/Guest accepted immediately
            if (status1 != 0xC0000016) { // STATUS_MORE_PROCESSING_REQUIRED
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
            putU16LE(body, 0, 25); // StructureSize
            body[2] = 0;           // Flags
            body[3] = 1;           // SecurityMode
            putU16LE(body, 12, 64 + 24); // SecurityBufferOffset
            putU16LE(body, 14, secBlob.length);
            System.arraycopy(secBlob, 0, body, 24, secBlob.length);
            return body;
        }

        private List<String> enumSharesSmb2() throws Exception {
            // 1. Tree Connect to \\host\IPC$
            String ipcPath = "\\\\" + host + "\\IPC$";
            byte[] pathBytes = toUtf16LE(ipcPath);
            byte[] tcBody = new byte[8 + pathBytes.length];
            putU16LE(tcBody, 0, 9);
            putU16LE(tcBody, 4, 64 + 8);
            putU16LE(tcBody, 6, pathBytes.length);
            System.arraycopy(pathBytes, 0, tcBody, 8, pathBytes.length);

            sendNetBios(buildSmb2Packet(0x0003, tcBody)); // SMB2_TREE_CONNECT
            byte[] tcResp = recvNetBios();
            if (getU32LE(tcResp, 8) != 0) {
                throw new Exception("SMB2 IPC$ TreeConnect failed");
            }
            smb2TreeId = getU32LE(tcResp, 36);

            // 2. SMB2 Create on "srvsvc"
            byte[] pipeName = toUtf16LE("srvsvc");
            byte[] crBody = new byte[56 + pipeName.length];
            putU16LE(crBody, 0, 57);
            putU32LE(crBody, 24, 0x0012019F); // DesiredAccess: Read/Write
            putU32LE(crBody, 32, 0x00000003); // ShareAccess: Read/Write
            putU32LE(crBody, 36, 0x00000001); // CreateDisposition: FILE_OPEN
            putU16LE(crBody, 44, 64 + 56);    // NameOffset
            putU16LE(crBody, 46, pipeName.length);
            System.arraycopy(pipeName, 0, crBody, 56, pipeName.length);

            sendNetBios(buildSmb2Packet(0x0005, crBody)); // SMB2_CREATE
            byte[] crResp = recvNetBios();
            if (getU32LE(crResp, 8) != 0) {
                throw new Exception("SMB2 srvsvc open failed");
            }
            byte[] fileId = new byte[16];
            System.arraycopy(crResp, 64 + 64, fileId, 0, 16);

            // 3. DCERPC Bind to SRVSVC via Write + Read
            byte[] rpcBind = buildDcerpcBindSrvsvc();
            smb2WritePipe(fileId, rpcBind);
            smb2ReadPipe(fileId); // Consume bind_ack

            // 4. DCERPC NetShareEnumAll Request via Write + Read
            byte[] rpcEnum = buildDcerpcNetShareEnum(host);
            smb2WritePipe(fileId, rpcEnum);
            byte[] rpcResp = smb2ReadPipe(fileId);

            // 5. Close pipe
            byte[] clBody = new byte[24];
            putU16LE(clBody, 0, 24);
            System.arraycopy(fileId, 0, clBody, 8, 16);
            sendNetBios(buildSmb2Packet(0x0006, clBody));
            recvNetBios();

            return parseSrvsvcNetShareEnumLevel1(rpcResp);
        }

        private void smb2WritePipe(byte[] fileId, byte[] data) throws Exception {
            byte[] wrBody = new byte[48 + data.length];
            putU16LE(wrBody, 0, 49);
            putU16LE(wrBody, 2, 64 + 48); // DataOffset
            putU32LE(wrBody, 4, data.length);
            System.arraycopy(fileId, 0, wrBody, 16, 16);
            System.arraycopy(data, 0, wrBody, 48, data.length);
            sendNetBios(buildSmb2Packet(0x0009, wrBody)); // SMB2_WRITE
            byte[] resp = recvNetBios();
            if (getU32LE(resp, 8) != 0) {
                throw new Exception("SMB2 pipe write failed");
            }
        }

        private byte[] smb2ReadPipe(byte[] fileId) throws Exception {
            byte[] rdBody = new byte[49];
            putU16LE(rdBody, 0, 49);
            putU32LE(rdBody, 4, 16384); // Length to read
            System.arraycopy(fileId, 0, rdBody, 16, 16);
            sendNetBios(buildSmb2Packet(0x0008, rdBody)); // SMB2_READ
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

        private byte[] buildSmb2Packet(int command, byte[] body) {
            byte[] pkt = new byte[64 + body.length];
            pkt[0] = (byte) 0xFE;
            pkt[1] = 'S';
            pkt[2] = 'M';
            pkt[3] = 'B';
            putU16LE(pkt, 4, 64); // Header StructureSize
            putU16LE(pkt, 12, command);
            putU16LE(pkt, 14, 31); // Credits requested
            putU64LE(pkt, 24, smb2MsgId++);
            putU32LE(pkt, 36, smb2TreeId);
            putU64LE(pkt, 40, smb2SessionId);
            System.arraycopy(body, 0, pkt, 64, body.length);
            return pkt;
        }

        // ---------------------------------------------------------------------
        // SMB1 IMPLEMENTATION
        // ---------------------------------------------------------------------

        private boolean negotiateSmb1() throws Exception {
            byte[] dialect = "\u0002NT LM 0.12\u0000".getBytes("ASCII");
            byte[] body = new byte[3 + dialect.length];
            body[0] = 0; // WordCount
            putU16LE(body, 1, dialect.length);
            System.arraycopy(dialect, 0, body, 3, dialect.length);

            sendNetBios(buildSmb1Packet((byte) 0x72, body)); // SMB_COM_NEGOTIATE
            byte[] resp = recvNetBios();
            return resp.length >= 32 && resp[0] == (byte) 0xFF && resp[1] == 'S' && getU32LE(resp, 9) == 0;
        }

        private void authenticateSmb1() throws Exception {
            // Minimal Null/Guest & NTLMSSP SMB_COM_SESSION_SETUP_ANDX (0x73)
            byte[] ntlmNeg = buildNtlmsspNegotiate();
            byte[] pkt1 = buildSmb1SessionSetupExtended(ntlmNeg);
            sendNetBios(pkt1);
            byte[] resp1 = recvNetBios();
            int status1 = getU32LE(resp1, 9);
            smb1Uid = getU16LE(resp1, 28);
            if (status1 == 0) return;

            if (status1 == 0xC0000016) { // STATUS_MORE_PROCESSING_REQUIRED
                byte[] challenge = extractNtlmsspFromPacket(resp1);
                byte[] ntlmAuth = buildNtlmsspAuth(challenge, user, pass);
                byte[] pkt2 = buildSmb1SessionSetupExtended(ntlmAuth);
                sendNetBios(pkt2);
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
            body[0] = 12; // WordCount = 12 (Extended Security)
            body[1] = (byte) 0xFF; // AndXCommand
            putU16LE(body, 5, 16644); // MaxBufferSize
            putU16LE(body, 7, 2);     // MaxMpxCount
            putU16LE(body, 9, 1);     // VcNumber
            putU16LE(body, 15, secBlob.length);
            putU32LE(body, 21, 0x800000D4); // Capabilities: Extended Security + NT SMBs + Unicode
            putU16LE(body, 25, bcc);
            System.arraycopy(secBlob, 0, body, 27, secBlob.length);
            System.arraycopy(nativeOs, 0, body, 27 + secBlob.length, nativeOs.length);
            return buildSmb1Packet((byte) 0x73, body);
        }

        private List<String> enumSharesSmb1() throws Exception {
            // 1. TreeConnect AndX (0x75) to \\host\IPC$
            String path = "\\\\" + host.toUpperCase() + "\\IPC$\u0000";
            byte[] pathBytes = path.getBytes("ASCII");
            byte[] srvType = "IPC\u0000".getBytes("ASCII");
            int bcc = 1 + pathBytes.length + srvType.length;
            byte[] tcBody = new byte[1 + 8 + 2 + bcc];
            tcBody[0] = 4; // WordCount = 4
            tcBody[1] = (byte) 0xFF;
            putU16LE(tcBody, 7, 1); // PasswordLen = 1 (null byte)
            putU16LE(tcBody, 9, bcc);
            tcBody[11] = 0; // Password
            System.arraycopy(pathBytes, 0, tcBody, 12, pathBytes.length);
            System.arraycopy(srvType, 0, tcBody, 12 + pathBytes.length, srvType.length);

            // Use ASCII flag for simple TreeConnect
            byte[] tcPkt = buildSmb1Packet((byte) 0x75, tcBody);
            tcPkt[11] = (byte) 0x01; // Flags2 without Unicode so ASCII path works reliably
            sendNetBios(tcPkt);
            byte[] tcResp = recvNetBios();
            if (getU32LE(tcResp, 9) != 0) {
                throw new Exception("SMB1 IPC$ TreeConnect failed");
            }
            smb1Tid = getU16LE(tcResp, 24);

            // 2. Try Microsoft RAP NetShareEnum (LANMAN pipe) first — fastest single-packet SMB1 share probe
            List<String> rapShares = trySmb1RapShareEnum();
            if (!rapShares.isEmpty()) {
                return rapShares;
            }
            throw new Exception("SMB1 connected, but server refused RAP share enumeration");
        }

        private List<String> trySmb1RapShareEnum() throws Exception {
            byte[] pipeName = "\\PIPE\\LANMAN\u0000".getBytes("ASCII");
            // RAP NetShareEnum: Function 0x0000, ParamDesc "WrLeh\0", DataDesc "B13BWz\0", Level 1, BufSize 4096
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
            body[0] = 14; // WordCount
            putU16LE(body, 1, rapParams.length); // TotalParameterCount
            putU16LE(body, 3, 0);                // TotalDataCount
            putU16LE(body, 5, 8);                // MaxParameterCount
            putU16LE(body, 7, 4096);             // MaxDataCount
            putU16LE(body, 19, rapParams.length);// ParameterCount
            putU16LE(body, 21, paramOffset);     // ParameterOffset
            putU16LE(body, 23, 0);               // DataCount
            putU16LE(body, 25, dataOffset);      // DataOffset
            putU16LE(body, 29, bcc);
            System.arraycopy(pipeName, 0, body, 31, pipeName.length);
            System.arraycopy(rapParams, 0, body, 31 + pipeName.length, rapParams.length);

            byte[] pkt = buildSmb1Packet((byte) 0x25, body); // SMB_COM_TRANSACTION
            pkt[11] = 0x01; // ASCII strings
            sendNetBios(pkt);
            byte[] resp = recvNetBios();
            List<String> list = new ArrayList<String>();
            if (getU32LE(resp, 9) != 0 || resp.length < 55) return list;

            int respParamOff = getU16LE(resp, 32 + 9);
            int respDataCount = getU16LE(resp, 32 + 13);
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

        private byte[] buildSmb1Packet(byte command, byte[] body) {
            byte[] pkt = new byte[32 + body.length];
            pkt[0] = (byte) 0xFF;
            pkt[1] = 'S';
            pkt[2] = 'M';
            pkt[3] = 'B';
            pkt[4] = command;
            pkt[9] = 0x18; // Flags
            putU16LE(pkt, 10, 0xC803); // Flags2: Unicode, NT Status, Extended Security
            putU16LE(pkt, 24, smb1Tid);
            putU16LE(pkt, 26, 0x1234); // PID
            putU16LE(pkt, 28, smb1Uid);
            putU16LE(pkt, 30, smb1Mid++);
            System.arraycopy(body, 0, pkt, 32, body.length);
            return pkt;
        }

        // ---------------------------------------------------------------------
        // DCERPC SRVSVC HELPERS (NetShareEnumAll Opnum 15, Level 1)
        // ---------------------------------------------------------------------

        private byte[] buildDcerpcBindSrvsvc() {
            // 72-byte standard DCERPC Bind PDU for SRVSVC UUID 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0
            return new byte[] {
                0x05, 0x00, 0x0B, 0x03, 0x10, 0x00, 0x00, 0x00,
                0x48, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
                (byte) 0xB8, 0x10, (byte) 0xB8, 0x10, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
                // SRVSVC UUID
                (byte) 0xC8, 0x4F, 0x32, 0x4B, 0x70, 0x16, (byte) 0xD3, 0x01,
                0x12, 0x78, 0x5A, 0x47, (byte) 0xBF, 0x6E, (byte) 0xE1, (byte) 0x88,
                0x03, 0x00, 0x00, 0x00,
                // NDR Transfer Syntax 8a885d04-1ceb-11c9-9fe8-08002b104860 v2.0
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
            writeU32LE(stub, 0x00020000); // Referent ID for ServerUnc
            writeU32LE(stub, charCount);  // MaxCount
            writeU32LE(stub, 0);          // Offset
            writeU32LE(stub, charCount);  // ActualCount
            stub.write(uncUtf16);
            for (int i = 0; i < pad; i++) stub.write(0);

            writeU32LE(stub, 1);          // Level = 1
            writeU32LE(stub, 1);          // Ctr switch = 1
            writeU32LE(stub, 0x00020004); // Referent ID for ShareInfo1Ctr
            writeU32LE(stub, 0);          // Count = 0
            writeU32LE(stub, 0);          // Null array pointer
            writeU32LE(stub, 0xFFFFFFFF); // PreferedMaximumLength = -1
            writeU32LE(stub, 0);          // Null ResumeHandle

            byte[] stubBytes = stub.toByteArray();
            byte[] pdu = new byte[24 + stubBytes.length];
            pdu[0] = 5;
            pdu[1] = 0;
            pdu[2] = 0; // Request PDU
            pdu[3] = 3; // First + Last Frag
            pdu[4] = 0x10;
            putU16LE(pdu, 8, pdu.length);
            putU32LE(pdu, 12, 2); // CallId
            putU32LE(pdu, 16, stubBytes.length);
            putU16LE(pdu, 22, 15); // Opnum 15 = NetrShareEnum
            System.arraycopy(stubBytes, 0, pdu, 24, stubBytes.length);
            return pdu;
        }

        private List<String> parseSrvsvcNetShareEnumLevel1(byte[] pdu) {
            List<String> result = new ArrayList<String>();
            if (pdu == null || pdu.length < 48) return result;
            // Stub begins at offset 24 of DCERPC response PDU:
            // [24: Level(4)][28: Switch(4)][32: CtrPtr(4)][36: EntriesRead(4)][40: ArrayPtr(4)][44: MaxCount(4)]
            int entriesRead = getU32LE(pdu, 36);
            int maxCount = getU32LE(pdu, 44);
            int count = (entriesRead > 0 && entriesRead < 512) ? entriesRead : maxCount;
            if (count <= 0 || count > 512) return result;

            // Array of SHARE_INFO_1 headers starts at 48; each header is 12 bytes:
            // [NamePtr(4)][Type(4)][CommentPtr(4)]
            int pos = 48;
            int[] namePtrs = new int[count];
            int[] commentPtrs = new int[count];
            for (int i = 0; i < count; i++) {
                if (pos + 12 > pdu.length) return result;
                namePtrs[i] = getU32LE(pdu, pos);
                commentPtrs[i] = getU32LE(pdu, pos + 8);
                pos += 12;
            }

            // Conformant/Varying Unicode strings follow in order: Name[i], Comment[i]
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
            // Strip trailing UTF-16 null terminator
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

        // ---------------------------------------------------------------------
        // NTLMSSP + NTLMv2 AUTHENTICATION (Zero External Dependencies)
        // ---------------------------------------------------------------------

        private byte[] buildNtlmsspNegotiate() {
            return new byte[] {
                'N', 'T', 'L', 'M', 'S', 'S', 'P', 0x00,
                0x01, 0x00, 0x00, 0x00, // Type 1
                0x07, (byte) 0x82, 0x08, (byte) 0xA2, // Flags: Unicode, NTLM, Extended Session Security
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
                // Anonymous / Guest Type 3 message
                byte[] anon = new byte[64];
                System.arraycopy("NTLMSSP\u0000".getBytes("ASCII"), 0, anon, 0, 8);
                putU32LE(anon, 8, 3);
                putU32LE(anon, 60, 0xA2088205);
                return anon;
            }

            byte[] serverChallenge = new byte[8];
            System.arraycopy(type2, 24, serverChallenge, 0, 8);

            // Extract TargetInfo from Type 2 if present
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
            putU32LE(type3, 8, 3); // Type 3

            // NtChallengeResponseFields
            putU16LE(type3, 20, ntProofAndBlob.length);
            putU16LE(type3, 22, ntProofAndBlob.length);
            putU32LE(type3, 24, ntOff);

            // UserNameFields
            putU16LE(type3, 36, userBytes.length);
            putU16LE(type3, 38, userBytes.length);
            putU32LE(type3, 40, userOff);

            // WorkstationFields
            putU16LE(type3, 44, hostBytes.length);
            putU16LE(type3, 46, hostBytes.length);
            putU32LE(type3, 48, hostOff);

            putU32LE(type3, 60, 0xA2888205); // NegotiateFlags

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

        // Pure-Java MD4 so it never relies on BouncyCastle provider quirks across Android 1.6 - 14
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

        // ---------------------------------------------------------------------
        // NETBIOS FRAMING & BYTE HELPERS
        // ---------------------------------------------------------------------

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
            if (len <= 0 || len > 262144) {
                throw new Exception("Invalid NetBIOS frame length: " + len);
            }
            byte[] body = new byte[len];
            in.readFully(body);
            return body;
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

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
