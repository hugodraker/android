package com.example.asyncer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    public static final int REQ_PICK_LUA = 9001;
    public static final int REQ_PERMS = 9002;
    public static final int REQ_PICK_DB = 9003;

    private File iniFile;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Map<String, String>> jobsData = new HashMap<Integer, Map<String, String>>();
    private int currentJobIndex = 0;
    private boolean isUpdatingUI = false;

    // Global Options
    private CheckBox cbRunOnStart;
    private Spinner spJobSelect;

    // SQL HTTP Server Options
    private CheckBox cbEnableSqlServer;
    private EditText etHttpPort, etHttpUser, etHttpPass, etSqlDbPath;

    // Connection Fields
    private Spinner spProtocol;
    private EditText etHost, etPort, etUser, etPass;
    private Spinner spShares;
    private ArrayAdapter<String> shareAdapter;
    private final List<String> shareList = new ArrayList<String>();
    private EditText etRemoteSubPath;
    private Button btnProbeShares;

    // Sync Options
    private EditText etLocalDir;
    private Spinner spDirection;
    private Spinner spMode;
    private CheckBox cbRecursive, cbDeleteExtras;

    // Script / Command Options
    private EditText etExternalCmd;
    private TextView tvConsole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            requestWindowFeature(Window.FEATURE_LEFT_ICON);
        } catch (Throwable ignored) {}

        disableStrictModeSafely();
        ensureModernStoragePermissions();

        iniFile = new File(getFilesDir(), "asyncer.ini");
        File defaultSyncFolder = getDefaultSyncFolder();
        File defaultDbFile = getDefaultDbFile();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        // --- GLOBAL SETTINGS ---
        cbRunOnStart = new CheckBox(this);
        cbRunOnStart.setText("Start Asyncer automatically when phone boots");
        cbRunOnStart.setChecked(true);
        root.addView(cbRunOnStart);

        addSeparator(root);

        // --- SQLDROID HTTP SERVER SETTINGS ---
        TextView lblSqlSrv = new TextView(this);
        lblSqlSrv.setText("--- SQLDroid HTTP Server & Remote API ---");
        root.addView(lblSqlSrv);

        cbEnableSqlServer = new CheckBox(this);
        cbEnableSqlServer.setText("Enable SQL & Sync HTTP Server");
        root.addView(cbEnableSqlServer);

        etHttpPort = addLabeledInput(root, "HTTP Server Port:", "8080", false);
        etHttpUser = addLabeledInput(root, "HTTP Login Username:", "admin", false);
        etHttpPass = addLabeledInput(root, "HTTP Login Password:", "", true);

        TextView lblDb = new TextView(this);
        lblDb.setText("SQLite Database Path (Internal Storage or SD Card):");
        root.addView(lblDb);

        LinearLayout dbRow = new LinearLayout(this);
        dbRow.setOrientation(LinearLayout.HORIZONTAL);
        etSqlDbPath = new EditText(this);
        etSqlDbPath.setSingleLine(true);
        etSqlDbPath.setText(defaultDbFile.getAbsolutePath());
        dbRow.addView(etSqlDbPath, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnBrowseDb = new Button(this);
        btnBrowseDb.setText("Browse...");
        btnBrowseDb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File cur = new File(etSqlDbPath.getText().toString().trim());
                File startDir = (cur.getParentFile() != null && cur.getParentFile().exists())
                        ? cur.getParentFile() : getDefaultStartDir();
                showDbFileBrowser(startDir);
            }
        });
        dbRow.addView(btnBrowseDb);
        root.addView(dbRow);

        cbEnableSqlServer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isUpdatingUI) return;
                saveCurrentJobToMemory();
                saveAllToIni();
                applySqlHttpServerState();
            }
        });

        addSeparator(root);

        // --- PROFILE SELECTOR ---
        LinearLayout jobRow = new LinearLayout(this);
        jobRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView lblJob = new TextView(this);
        lblJob.setText("Sync Profile / Job: ");
        jobRow.addView(lblJob);

        spJobSelect = new Spinner(this);
        String[] jobNames = new String[] { "Job 1", "Job 2", "Job 3", "Job 4", "Job 5" };
        ArrayAdapter<String> jobAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, jobNames);
        jobAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spJobSelect.setAdapter(jobAdapter);
        jobRow.addView(spJobSelect, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(jobRow);

        addSeparator(root);

        // --- CONNECTION SETTINGS ---
        TextView lblConn = new TextView(this);
        lblConn.setText("--- Connection Settings ---");
        root.addView(lblConn);

        spProtocol = new Spinner(this);
        ArrayAdapter<String> protoAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                new String[] { "SMB (Windows Share)", "FTP (File Transfer Protocol)" });
        protoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProtocol.setAdapter(protoAdapter);
        root.addView(spProtocol);

        spProtocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingUI) return;
                String curPort = etPort.getText().toString().trim();
                if (position == 1 && ("445".equals(curPort) || curPort.length() == 0)) {
                    etPort.setText("21");
                } else if (position == 0 && ("21".equals(curPort) || curPort.length() == 0)) {
                    etPort.setText("445");
                }
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        etHost = addLabeledInput(root, "Host / IP:", "192.168.1.100", false);
        etPort = addLabeledInput(root, "Port (SMB: 445, FTP: 21):", "445", false);
        etUser = addLabeledInput(root, "Username:", "guest", false);
        etPass = addLabeledInput(root, "Password:", "", true);

        TextView lblShare = new TextView(this);
        lblShare.setText("Discovered Shares / FTP Folders:");
        root.addView(lblShare);

        LinearLayout shareRow = new LinearLayout(this);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);

        spShares = new Spinner(this);
        shareAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, shareList);
        shareAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spShares.setAdapter(shareAdapter);
        shareRow.addView(spShares, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnProbeShares = new Button(this);
        btnProbeShares.setText("Test / Probe");
        btnProbeShares.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                probeServerSharesOrFtpDirs();
            }
        });
        shareRow.addView(btnProbeShares);
        root.addView(shareRow);

        etRemoteSubPath = addLabeledInput(root, "Custom Share or Remote Path Override (optional):", "", false);

        addSeparator(root);

        // --- SYNC OPTIONS ---
        TextView lblSync = new TextView(this);
        lblSync.setText("--- Synchronization Settings ---");
        root.addView(lblSync);

        etLocalDir = addLabeledInput(root, "Local Sync Folder (created if missing):", defaultSyncFolder.getAbsolutePath(), false);

        TextView lblDir = new TextView(this);
        lblDir.setText("Direction:");
        root.addView(lblDir);
        spDirection = new Spinner(this);
        ArrayAdapter<String> dirAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                new String[] { "Local -> Remote", "Remote -> Local", "Bi-Directional (Two-Way)" });
        dirAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDirection.setAdapter(dirAdapter);
        root.addView(spDirection);

        TextView lblMode = new TextView(this);
        lblMode.setText("File Collision Mode:");
        root.addView(lblMode);
        spMode = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item,
                new String[] { "Update (Skip older/same)", "Overwrite All", "Skip Existing" });
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(modeAdapter);
        root.addView(spMode);

        cbRecursive = new CheckBox(this);
        cbRecursive.setText("Recursive (Include subfolders)");
        cbRecursive.setChecked(true);
        root.addView(cbRecursive);

        cbDeleteExtras = new CheckBox(this);
        cbDeleteExtras.setText("Mirror (Delete extra files on destination)");
        root.addView(cbDeleteExtras);

        addSeparator(root);

        // --- SCRIPTS & COMMANDS ---
        TextView lblCmd = new TextView(this);
        lblCmd.setText("Associated LuaJ Script (.lua) or External Command:");
        root.addView(lblCmd);

        LinearLayout cmdRow = new LinearLayout(this);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        etExternalCmd = new EditText(this);
        etExternalCmd.setSingleLine(true);
        etExternalCmd.setHint("/sdcard/sync/script.lua");
        cmdRow.addView(etExternalCmd, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnBrowseLua = new Button(this);
        btnBrowseLua.setText("Browse...");
        btnBrowseLua.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File startDir = new File(etLocalDir.getText().toString().trim());
                if (!startDir.exists()) startDir = getDefaultSyncFolder();
                showLuaFileBrowser(startDir);
            }
        });
        cmdRow.addView(btnBrowseLua);

        Button btnRunCmd = new Button(this);
        btnRunCmd.setText("Run");
        btnRunCmd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentJobToMemory();
                saveAllToIni();
                executeCommandOrLua(etExternalCmd.getText().toString().trim());
            }
        });
        cmdRow.addView(btnRunCmd);
        root.addView(cmdRow);

        // --- ACTION BUTTONS ---
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 10, 0, 10);

        Button btnSave = new Button(this);
        btnSave.setText("Save All");
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentJobToMemory();
                saveAllToIni();
                applySqlHttpServerState();
                Toast.makeText(MainActivity.this, "Settings Saved", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnStartSvc = new Button(this);
        btnStartSvc.setText("Start Service");
        btnStartSvc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentJobToMemory();
                saveAllToIni();
                try {
                    BootReceiver.startServiceCompat(MainActivity.this, new Intent(MainActivity.this, AsyncerService.class));
                    appendLog("Background AsyncerService started.");
                } catch (Exception e) {
                    appendLog("Failed to start service: " + e.getMessage());
                }
            }
        });
        btnRow.addView(btnStartSvc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(btnRow);

        Button btnSyncNow = new Button(this);
        btnSyncNow.setText("Sync Current Job & Script Now");
        btnSyncNow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentJobToMemory();
                saveAllToIni();
                runSyncForegroundThread();
            }
        });
        root.addView(btnSyncNow);

        tvConsole = new TextView(this);
        tvConsole.setPadding(6, 12, 6, 12);
        tvConsole.setText("Asyncer ready.\n");
        root.addView(tvConsole);

        scroll.addView(root);
        setContentView(scroll);

        try {
            getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, android.R.drawable.ic_popup_sync);
        } catch (Throwable ignored) {}

        spJobSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingUI) return;
                saveCurrentJobToMemory();
                currentJobIndex = position;
                loadJobToUI(currentJobIndex);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadAllFromIni();
        applySqlHttpServerState();

        if (savedInstanceState == null && (cbRunOnStart.isChecked() || cbEnableSqlServer.isChecked())) {
            try {
                BootReceiver.startServiceCompat(this, new Intent(this, AsyncerService.class));
            } catch (Exception ignored) {}
        }
    }

    private void applySqlHttpServerState() {
        SqlHttpServer srv = SqlHttpServer.getInstance(this);
        srv.setLogger(new SyncRunner.LogCallback() {
            public void onLog(String msg) {
                appendLog(msg);
            }
        });
        if (cbEnableSqlServer.isChecked()) {
            int port = parseIntSafe(etHttpPort.getText().toString().trim(), 8080);
            srv.start(port);
            appendLog("SQL HTTP Server enabled on port " + port);
            try {
                BootReceiver.startServiceCompat(this, new Intent(this, AsyncerService.class));
            } catch (Exception ignored) {}
        } else {
            if (srv.isRunning()) {
                srv.stop();
                appendLog("SQL HTTP Server stopped.");
            }
        }
    }

    private File getDefaultDbFile() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                return new File(ext, "asyncer.db");
            }
        } catch (Throwable ignored) {}
        return new File(getFilesDir(), "asyncer.db");
    }

    private File getDefaultStartDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) return ext;
        } catch (Throwable ignored) {}
        return getFilesDir();
    }

    private void ensureModernStoragePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Method isMgr = Environment.class.getMethod("isExternalStorageManager");
                Boolean granted = (Boolean) isMgr.invoke(null);
                if (granted != null && !granted.booleanValue()) {
                    Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                Method checkSelf = Activity.class.getMethod("checkSelfPermission", String.class);
                Integer res = (Integer) checkSelf.invoke(this, "android.permission.WRITE_EXTERNAL_STORAGE");
                if (res != null && res.intValue() != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerms = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[] {
                            "android.permission.READ_EXTERNAL_STORAGE",
                            "android.permission.WRITE_EXTERNAL_STORAGE"
                    }, Integer.valueOf(REQ_PERMS));
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Method checkSelf = Activity.class.getMethod("checkSelfPermission", String.class);
                Integer res = (Integer) checkSelf.invoke(this, "android.permission.POST_NOTIFICATIONS");
                if (res != null && res.intValue() != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerms = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[] { "android.permission.POST_NOTIFICATIONS" }, Integer.valueOf(REQ_PERMS));
                }
            }
        } catch (Throwable ignored) {}
    }

    private File getDefaultSyncFolder() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                File syncDir = new File(ext, "sync");
                if (syncDir.exists() || syncDir.mkdirs()) {
                    return syncDir;
                }
            }
        } catch (Throwable ignored) {}
        File fallback = new File(getFilesDir(), "sync");
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    private void disableStrictModeSafely() {
        try {
            Class<?> smClass = Class.forName("android.os.StrictMode");
            Class<?> tpClass = Class.forName("android.os.StrictMode$ThreadPolicy");
            Class<?> bldClass = Class.forName("android.os.StrictMode$ThreadPolicy$Builder");
            Object bld = bldClass.getConstructor().newInstance();
            bld = bldClass.getMethod("permitAll").invoke(bld);
            Object pol = bldClass.getMethod("build").invoke(bld);
            smClass.getMethod("setThreadPolicy", tpClass).invoke(null, pol);
        } catch (Throwable ignored) {}
    }

    private EditText addLabeledInput(LinearLayout parent, String label, String defVal, boolean isPass) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(defVal);
        if (isPass) et.setInputType(0x00000081);
        parent.addView(et);
        return et;
    }

    private void addSeparator(LinearLayout parent) {
        View sep = new View(this);
        sep.setBackgroundColor(Color.DKGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2);
        lp.setMargins(0, 15, 0, 15);
        parent.addView(sep, lp);
    }

    private String getEffectiveRemotePath() {
        String override = etRemoteSubPath.getText().toString().trim();
        if (override.length() > 0) return override;
        Object sel = spShares.getSelectedItem();
        return sel != null ? sel.toString().trim() : "";
    }

    // =========================================================================
    // INI PERSISTENCE
    // =========================================================================

    private void loadAllFromIni() {
        if (!iniFile.exists()) {
            saveCurrentJobToMemory();
            saveAllToIni();
            loadJobToUI(0);
            return;
        }
        BufferedReader br = null;
        isUpdatingUI = true;
        try {
            br = new BufferedReader(new FileReader(iniFile));
            String line;
            String currentSection = "";
            Map<String, String> currentMap = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).toUpperCase();
                    if (currentSection.startsWith("JOB_")) {
                        int jIdx = parseIntSafe(currentSection.substring(4), 0);
                        if (!jobsData.containsKey(jIdx)) jobsData.put(jIdx, new HashMap<String, String>());
                        currentMap = jobsData.get(jIdx);
                    } else {
                        currentMap = null;
                    }
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();

                if ("GLOBAL".equals(currentSection)) {
                    if ("run_on_start".equals(k)) cbRunOnStart.setChecked("1".equals(v) || "true".equalsIgnoreCase(v));
                    else if ("last_job".equals(k)) currentJobIndex = parseIntSafe(v, 0);
                    else if ("sql_server_enabled".equals(k)) cbEnableSqlServer.setChecked("1".equals(v) || "true".equalsIgnoreCase(v));
                    else if ("http_port".equals(k) && v.length() > 0) etHttpPort.setText(v);
                    else if ("http_user".equals(k)) etHttpUser.setText(v);
                    else if ("http_pass".equals(k)) etHttpPass.setText(v);
                    else if ("sql_db_path".equals(k) && v.length() > 0) etSqlDbPath.setText(v);
                } else if (currentMap != null) {
                    currentMap.put(k, v);
                }
            }
        } catch (Exception e) {
            appendLog("Failed to load INI: " + e.getMessage());
        } finally {
            isUpdatingUI = false;
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }

        if (currentJobIndex < 0 || currentJobIndex >= spJobSelect.getCount()) currentJobIndex = 0;
        isUpdatingUI = true;
        spJobSelect.setSelection(currentJobIndex);
        isUpdatingUI = false;
        loadJobToUI(currentJobIndex);
    }

    private void saveAllToIni() {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(iniFile, false);
            StringBuilder sb = new StringBuilder();

            sb.append("[GLOBAL]\r\n");
            sb.append("run_on_start=").append(cbRunOnStart.isChecked() ? "1" : "0").append("\r\n");
            sb.append("last_job=").append(currentJobIndex).append("\r\n");
            sb.append("sql_server_enabled=").append(cbEnableSqlServer.isChecked() ? "1" : "0").append("\r\n");
            sb.append("http_port=").append(etHttpPort.getText().toString().trim()).append("\r\n");
            sb.append("http_user=").append(etHttpUser.getText().toString().trim()).append("\r\n");
            sb.append("http_pass=").append(etHttpPass.getText().toString()).append("\r\n");
            sb.append("sql_db_path=").append(etSqlDbPath.getText().toString().trim()).append("\r\n\r\n");

            for (int i = 0; i < 5; i++) {
                sb.append("[JOB_").append(i).append("]\r\n");
                Map<String, String> map = jobsData.get(i);
                if (map != null) {
                    for (Map.Entry<String, String> entry : map.entrySet()) {
                        sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\r\n");
                    }
                }
                sb.append("\r\n");
            }
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception e) {
            appendLog("Failed to save INI: " + e.getMessage());
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    private void loadJobToUI(int jobIdx) {
        isUpdatingUI = true;
        Map<String, String> map = jobsData.get(jobIdx);
        if (map == null) map = new HashMap<String, String>();

        int proto = parseIntSafe(map.get("protocol"), 0);
        spProtocol.setSelection(proto);
        etHost.setText(map.containsKey("host") ? map.get("host") : "192.168.1.100");
        etPort.setText(map.containsKey("port") ? map.get("port") : (proto == 1 ? "21" : "445"));
        etUser.setText(map.containsKey("user") ? map.get("user") : "guest");
        etPass.setText(map.containsKey("pass") ? map.get("pass") : "");

        shareList.clear();
        String savedShares = map.get("shares");
        if (savedShares != null && savedShares.length() > 0) {
            String[] parts = savedShares.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                String s = parts[i].trim();
                if (s.length() > 0 && !shareList.contains(s)) shareList.add(s);
            }
        }
        String activeShare = map.containsKey("remote_path") ? map.get("remote_path") : "";
        if (activeShare.length() > 0 && !shareList.contains(activeShare)) {
            shareList.add(0, activeShare);
        }
        if (shareList.isEmpty()) {
            shareList.add(proto == 1 ? "/" : "PUBLIC");
        }
        shareAdapter.notifyDataSetChanged();
        if (activeShare.length() > 0) {
            int idx = shareList.indexOf(activeShare);
            if (idx >= 0) spShares.setSelection(idx);
        }
        etRemoteSubPath.setText(map.containsKey("remote_override") ? map.get("remote_override") : "");

        String defSync = getDefaultSyncFolder().getAbsolutePath();
        String ld = map.containsKey("local_dir") && map.get("local_dir").length() > 0 ? map.get("local_dir") : defSync;
        etLocalDir.setText(ld);

        spDirection.setSelection(parseIntSafe(map.get("direction"), 0));
        spMode.setSelection(parseIntSafe(map.get("mode"), 0));

        cbRecursive.setChecked(!"0".equals(map.get("recursive")));
        cbDeleteExtras.setChecked("1".equals(map.get("delete_extras")));
        etExternalCmd.setText(map.containsKey("ext_cmd") ? map.get("ext_cmd") : "");

        isUpdatingUI = false;
    }

    private void saveCurrentJobToMemory() {
        if (isUpdatingUI) return;
        Map<String, String> map = jobsData.get(currentJobIndex);
        if (map == null) {
            map = new HashMap<String, String>();
            jobsData.put(currentJobIndex, map);
        }
        String effRemote = getEffectiveRemotePath();
        if (effRemote.length() > 0 && !shareList.contains(effRemote)) {
            shareList.add(effRemote);
            shareAdapter.notifyDataSetChanged();
        }
        StringBuilder sbShares = new StringBuilder();
        for (int i = 0; i < shareList.size(); i++) {
            if (i > 0) sbShares.append('|');
            sbShares.append(shareList.get(i));
        }

        File localFolder = new File(etLocalDir.getText().toString().trim());
        if (!localFolder.exists()) {
            try { localFolder.mkdirs(); } catch (Throwable ignored) {}
        }

        map.put("protocol", String.valueOf(spProtocol.getSelectedItemPosition()));
        map.put("host", etHost.getText().toString().trim());
        map.put("port", etPort.getText().toString().trim());
        map.put("user", etUser.getText().toString().trim());
        map.put("pass", etPass.getText().toString());
        map.put("remote_path", effRemote);
        map.put("remote_override", etRemoteSubPath.getText().toString().trim());
        map.put("shares", sbShares.toString());
        map.put("local_dir", localFolder.getAbsolutePath());
        map.put("direction", String.valueOf(spDirection.getSelectedItemPosition()));
        map.put("mode", String.valueOf(spMode.getSelectedItemPosition()));
        map.put("recursive", cbRecursive.isChecked() ? "1" : "0");
        map.put("delete_extras", cbDeleteExtras.isChecked() ? "1" : "0");
        map.put("ext_cmd", etExternalCmd.getText().toString().trim());
    }

    // =========================================================================
    // PROBE & TEST BUTTON
    // =========================================================================

    private void probeServerSharesOrFtpDirs() {
        final int proto = spProtocol.getSelectedItemPosition();
        final String host = etHost.getText().toString().trim();
        final int port = parseIntSafe(etPort.getText().toString().trim(), proto == 1 ? 21 : 445);
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();

        if (host.length() == 0) {
            appendLog("Error: Host is empty.");
            return;
        }

        btnProbeShares.setEnabled(false);
        appendLog("Testing connection & probing " + (proto == 1 ? "FTP" : "SMB") + " on " + host + ":" + port + "...");

        new Thread(new Runnable() {
            public void run() {
                final List<String> discovered = new ArrayList<String>();
                final List<String> sampleListing = new ArrayList<String>();
                try {
                    if (proto == 1) {
                        FtpClient ftp = new FtpClient();
                        try {
                            if (!ftp.connect(host, port, user, pass)) {
                                throw new Exception("FTP login failed");
                            }
                            discovered.addAll(ftp.probeRootDirectories("/"));
                            List<FtpClient.FtpItem> rootItems = ftp.listSingleDirectory("/", "");
                            for (int i = 0; i < rootItems.size(); i++) {
                                FtpClient.FtpItem it = rootItems.get(i);
                                sampleListing.add((it.isDirectory ? "[DIR] " : "      ") + it.name);
                            }
                        } finally {
                            ftp.disconnect();
                        }
                    } else {
                        SmbClient smb = new SmbClient();
                        try {
                            discovered.addAll(smb.probeShares(host, port, user, pass));
                            for (int i = 0; i < discovered.size(); i++) {
                                sampleListing.add("[SHARE] " + discovered.get(i));
                            }
                            if (!discovered.isEmpty()) {
                                SmbClient.ConnectionProfile p = new SmbClient.ConnectionProfile();
                                p.host = host;
                                p.port = port;
                                p.share = discovered.get(0);
                                p.username = user;
                                p.password = pass;
                                if (smb.connect(p)) {
                                    List<SmbClient.DirectoryItem> items = smb.fetchRemoteDirContents("");
                                    sampleListing.add("--- Contents of \\\\" + host + "\\" + p.share + " ---");
                                    for (int i = 0; i < Math.min(items.size(), 40); i++) {
                                        SmbClient.DirectoryItem di = items.get(i);
                                        sampleListing.add((di.isDirectory ? "[DIR] " : "      ") + di.path);
                                    }
                                }
                            }
                        } finally {
                            smb.disconnect();
                        }
                    }

                    uiHandler.post(new Runnable() {
                        public void run() {
                            btnProbeShares.setEnabled(true);
                            for (int i = 0; i < discovered.size(); i++) {
                                String s = discovered.get(i);
                                if (!shareList.contains(s)) shareList.add(s);
                            }
                            if (!discovered.isEmpty() && shareList.size() > 1) {
                                shareList.remove("PUBLIC");
                            }
                            shareAdapter.notifyDataSetChanged();
                            if (!discovered.isEmpty()) {
                                int idx = shareList.indexOf(discovered.get(0));
                                if (idx >= 0) spShares.setSelection(idx);
                            }
                            saveCurrentJobToMemory();
                            saveAllToIni();
                            appendLog("Probe succeeded: " + discovered.size() + " share(s)/folder(s) saved.");

                            showProbeListingDialog(host, sampleListing);
                        }
                    });
                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        public void run() {
                            btnProbeShares.setEnabled(true);
                            appendLog("Probe failed: " + e.getMessage());
                            Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void showProbeListingDialog(String host, List<String> lines) {
        StringBuilder sb = new StringBuilder();
        if (lines.isEmpty()) {
            sb.append("(Connected, but directory/share list is empty)");
        } else {
            for (int i = 0; i < lines.size(); i++) {
                sb.append(lines.get(i)).append("\n");
            }
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setPadding(24, 16, 24, 16);
        tv.setTextSize(13.0f);
        tv.setText(sb.toString().trim());
        sv.addView(tv);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Listing on " + host);
        b.setView(sv);
        b.setPositiveButton("OK", null);
        b.show();
    }

    // =========================================================================
    // FOREGROUND SYNC + ASSOCIATED SCRIPT
    // =========================================================================

    private void runSyncForegroundThread() {
        final int jobIdx = currentJobIndex;
        new Thread(new Runnable() {
            public void run() {
                String status = SyncRunner.runJobAndScript(MainActivity.this, MainActivity.this, jobIdx, true, new SyncRunner.LogCallback() {
                    public void onLog(String msg) {
                        appendLog(msg);
                    }
                });
                appendLog(status);
            }
        }).start();
    }

    private void appendLog(final String msg) {
        uiHandler.post(new Runnable() {
            public void run() {
                tvConsole.append(msg + "\n");
            }
        });
    }

    // =========================================================================
    // SQLITE DB FILE BROWSER
    // =========================================================================

    public void showDbFileBrowser(final File dir) {
        File[] files = null;
        try { files = dir.listFiles(); } catch (Throwable ignored) {}

        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        // Quick jump shortcuts
        entries.add(getFilesDir());
        labels.add("[App Internal Storage: " + getFilesDir().getAbsolutePath() + "]");

        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) {
            entries.add(ext);
            labels.add("[SD Card / External Storage: " + ext.getAbsolutePath() + "]");
        }

        if (dir != null && dir.getParentFile() != null) {
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
                    String n = files[i].getName().toLowerCase();
                    if (n.endsWith(".db") || n.endsWith(".sqlite") || n.endsWith(".sqlite3") || n.endsWith(".db3")) {
                        entries.add(files[i]);
                        labels.add(files[i].getName());
                    }
                }
            }
        }
        labels.add("Create New Database in This Folder...");
        labels.add("Use System File Picker...");

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(dir != null ? dir.getAbsolutePath() : "/");
        b.setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == labels.size() - 1) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("*/*");
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(intent, "Select SQLite Database"), REQ_PICK_DB);
                    } catch (Exception ignored) {}
                    return;
                }
                if (which == labels.size() - 2) {
                    final EditText etName = new EditText(MainActivity.this);
                    etName.setText("asyncer.db");
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("New SQLite Database Filename")
                            .setView(etName)
                            .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    String fn = etName.getText().toString().trim();
                                    if (fn.length() > 0) {
                                        File target = new File(dir, fn);
                                        etSqlDbPath.setText(target.getAbsolutePath());
                                        saveCurrentJobToMemory();
                                        saveAllToIni();
                                    }
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return;
                }

                File target = entries.get(which);
                if (target.isDirectory()) {
                    showDbFileBrowser(target);
                } else {
                    etSqlDbPath.setText(target.getAbsolutePath());
                    saveCurrentJobToMemory();
                    saveAllToIni();
                }
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    // =========================================================================
    // LUAJ BROWSER & RUNNER
    // =========================================================================

    public void showLuaFileBrowser(final File dir) {
        File[] files = null;
        try { files = dir.listFiles(); } catch (Throwable ignored) {}

        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        if (dir != null && dir.getParentFile() != null) {
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
                    String n = files[i].getName();
                    if (n.toLowerCase().endsWith(".lua") || n.toLowerCase().endsWith(".sh")) {
                        entries.add(files[i]);
                        labels.add(n);
                    }
                }
            }
        }
        labels.add("Use System File Picker...");

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(dir != null ? dir.getAbsolutePath() : "/");
        b.setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == labels.size() - 1) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("*/*");
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(intent, "Select .lua Script"), REQ_PICK_LUA);
                    } catch (Exception ignored) {}
                    return;
                }
                File target = entries.get(which);
                if (target.isDirectory()) showLuaFileBrowser(target);
                else {
                    etExternalCmd.setText(target.getAbsolutePath());
                    saveCurrentJobToMemory();
                    saveAllToIni();
                }
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = uri.getPath();
                String val = path != null ? path : uri.toString();
                if (requestCode == REQ_PICK_LUA) {
                    etExternalCmd.setText(val);
                } else if (requestCode == REQ_PICK_DB) {
                    etSqlDbPath.setText(val);
                }
                saveCurrentJobToMemory();
                saveAllToIni();
            }
        }
    }

    public void executeCommandOrLua(final String cmdOrPath) {
        if (cmdOrPath == null || cmdOrPath.length() == 0) return;
        final Map<String, String> jobCtx = jobsData.get(currentJobIndex);
        new Thread(new Runnable() {
            public void run() {
                String res = SyncRunner.executeCommandOrLua(MainActivity.this, MainActivity.this, cmdOrPath, jobCtx, new SyncRunner.LogCallback() {
                    public void onLog(String msg) {
                        appendLog(msg);
                    }
                });
                appendLog("Finished: " + res);
            }
        }).start();
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}