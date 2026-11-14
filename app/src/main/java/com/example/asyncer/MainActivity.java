package com.example.asyncer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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

    private File iniFile;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Map<Integer, Map<String, String>> jobsData = new HashMap<Integer, Map<String, String>>();
    private int currentJobIndex = 0;
    private boolean isUpdatingUI = false;

    // Global Options
    private CheckBox cbRunOnStart;
    private Spinner spJobSelect;

    // Job Configuration Fields
    private Spinner spProtocol;
    private EditText etHost, etPort, etUser, etPass;
    private EditText etRemotePath;
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
        iniFile = new File(getFilesDir(), "asyncer.ini");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        // --- GLOBAL SETTINGS ---
        cbRunOnStart = new CheckBox(this);
        cbRunOnStart.setText("Run background service on start");
        root.addView(cbRunOnStart);

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
        ArrayAdapter<String> protoAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[]{"SMB (Windows Share)", "FTP (File Transfer Protocol)"});
        protoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProtocol.setAdapter(protoAdapter);
        root.addView(spProtocol);

        etHost = addLabeledInput(root, "Host / IP:", "192.168.1.100", false);
        etPort = addLabeledInput(root, "Port (SMB: 445, FTP: 21):", "445", false);
        etUser = addLabeledInput(root, "Username:", "guest", false);
        etPass = addLabeledInput(root, "Password:", "", true);

        LinearLayout remoteRow = new LinearLayout(this);
        remoteRow.setOrientation(LinearLayout.HORIZONTAL);
        
        LinearLayout remoteInputLayout = new LinearLayout(this);
        remoteInputLayout.setOrientation(LinearLayout.VERTICAL);
        TextView lblRemote = new TextView(this);
        lblRemote.setText("Remote Path / Share:");
        remoteInputLayout.addView(lblRemote);
        etRemotePath = new EditText(this);
        etRemotePath.setSingleLine(true);
        remoteInputLayout.addView(etRemotePath);
        
        remoteRow.addView(remoteInputLayout, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnProbeShares = new Button(this);
        btnProbeShares.setText("Probe\nShares");
        btnProbeShares.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { probeSmbShares(); }
        });
        remoteRow.addView(btnProbeShares);
        root.addView(remoteRow);

        addSeparator(root);

        // --- SYNC OPTIONS ---
        TextView lblSync = new TextView(this);
        lblSync.setText("--- Synchronization Settings ---");
        root.addView(lblSync);

        etLocalDir = addLabeledInput(root, "Local Sync Folder:", "", false);
        File defDir = new File(Environment.getExternalStorageDirectory(), "sync");
        etLocalDir.setText(defDir.getAbsolutePath());

        TextView lblDir = new TextView(this);
        lblDir.setText("Direction:");
        root.addView(lblDir);
        spDirection = new Spinner(this);
        ArrayAdapter<String> dirAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[]{"Local ➔ Remote", "Remote ➔ Local", "Bi-Directional (Two-Way)"});
        dirAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDirection.setAdapter(dirAdapter);
        root.addView(spDirection);

        TextView lblMode = new TextView(this);
        lblMode.setText("File Collision Mode:");
        root.addView(lblMode);
        spMode = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[]{"Update (Skip older/same)", "Overwrite All", "Skip Existing"});
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
        lblCmd.setText("Run LuaJ Script (.lua) or Command:");
        root.addView(lblCmd);

        LinearLayout cmdRow = new LinearLayout(this);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        etExternalCmd = new EditText(this);
        etExternalCmd.setSingleLine(true);
        etExternalCmd.setHint("/sdcard/script.lua");
        cmdRow.addView(etExternalCmd, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnBrowseLua = new Button(this);
        btnBrowseLua.setText("Browse...");
        btnBrowseLua.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLuaFileBrowser(new File(Environment.getExternalStorageDirectory(), "sync")); }
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
                    startService(new Intent(MainActivity.this, AsyncerService.class));
                    appendLog("Background AsyncerService started.");
                } catch (Exception e) {
                    appendLog("Failed to start service: " + e.getMessage());
                }
            }
        });
        btnRow.addView(btnStartSvc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(btnRow);

        Button btnSyncNow = new Button(this);
        btnSyncNow.setText("Sync Now (Foreground)");
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
        tvConsole.setText("Asyncer initialized.\n");
        root.addView(tvConsole);

        scroll.addView(root);
        setContentView(scroll);

        // Events & Initialization
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
        
        // Auto-Start Check
        if (savedInstanceState == null && cbRunOnStart.isChecked()) {
            appendLog("Run on start enabled. Triggering AsyncerService...");
            try { startService(new Intent(this, AsyncerService.class)); } catch (Exception ignored) {}
        }
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

    // =========================================================================
    // INI CONFIGURATION (Multiple Jobs Support)
    // =========================================================================

    private void loadAllFromIni() {
        if (!iniFile.exists()) {
            loadJobToUI(0);
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(iniFile));
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

                if (currentSection.equals("GLOBAL")) {
                    if (k.equals("run_on_start")) cbRunOnStart.setChecked(v.equals("1") || v.equalsIgnoreCase("true"));
                    if (k.equals("last_job")) currentJobIndex = parseIntSafe(v, 0);
                } else if (currentMap != null) {
                    currentMap.put(k, v);
                }
            }
            br.close();
        } catch (Exception e) {
            appendLog("Failed to load INI: " + e.getMessage());
        }

        if (currentJobIndex >= spJobSelect.getCount()) currentJobIndex = 0;
        spJobSelect.setSelection(currentJobIndex);
        loadJobToUI(currentJobIndex);
    }

    private void saveAllToIni() {
        try {
            FileOutputStream fos = new FileOutputStream(iniFile, false);
            StringBuilder sb = new StringBuilder();
            
            sb.append("[GLOBAL]\r\n");
            sb.append("run_on_start=").append(cbRunOnStart.isChecked() ? "1" : "0").append("\r\n");
            sb.append("last_job=").append(currentJobIndex).append("\r\n\r\n");

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
            fos.close();
        } catch (Exception e) {
            appendLog("Failed to save INI: " + e.getMessage());
        }
    }

    private void loadJobToUI(int jobIdx) {
        isUpdatingUI = true;
        Map<String, String> map = jobsData.get(jobIdx);
        if (map == null) map = new HashMap<String, String>();

        spProtocol.setSelection(parseIntSafe(map.get("protocol"), 0));
        etHost.setText(map.containsKey("host") ? map.get("host") : "192.168.1.100");
        etPort.setText(map.containsKey("port") ? map.get("port") : "445");
        etUser.setText(map.containsKey("user") ? map.get("user") : "guest");
        etPass.setText(map.containsKey("pass") ? map.get("pass") : "");
        etRemotePath.setText(map.containsKey("remote_path") ? map.get("remote_path") : "");
        
        String ld = map.containsKey("local_dir") ? map.get("local_dir") : new File(Environment.getExternalStorageDirectory(), "sync").getAbsolutePath();
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
        map.put("protocol", String.valueOf(spProtocol.getSelectedItemPosition()));
        map.put("host", etHost.getText().toString().trim());
        map.put("port", etPort.getText().toString().trim());
        map.put("user", etUser.getText().toString().trim());
        map.put("pass", etPass.getText().toString());
        map.put("remote_path", etRemotePath.getText().toString().trim());
        map.put("local_dir", etLocalDir.getText().toString().trim());
        map.put("direction", String.valueOf(spDirection.getSelectedItemPosition()));
        map.put("mode", String.valueOf(spMode.getSelectedItemPosition()));
        map.put("recursive", cbRecursive.isChecked() ? "1" : "0");
        map.put("delete_extras", cbDeleteExtras.isChecked() ? "1" : "0");
        map.put("ext_cmd", etExternalCmd.getText().toString().trim());
    }

    // =========================================================================
    // SHARE PROBER (Tooltip popup logic)
    // =========================================================================

    private void probeSmbShares() {
        final String host = etHost.getText().toString().trim();
        final int port = parseIntSafe(etPort.getText().toString().trim(), 445);
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();

        if (host.length() == 0 || spProtocol.getSelectedItemPosition() == 1) {
            appendLog("Error: Invalid host or FTP selected. Probing requires SMB.");
            return;
        }

        btnProbeShares.setEnabled(false);
        appendLog("Probing shares on " + host + ":" + port + "...");

        new Thread(new Runnable() {
            public void run() {
                SmbClient client = new SmbClient();
                try {
                    final List<String> shares = client.probeShares(host, port, user, pass);
                    uiHandler.post(new Runnable() {
                        public void run() {
                            btnProbeShares.setEnabled(true);
                            if (shares.isEmpty()) {
                                Toast.makeText(MainActivity.this, "No shares found.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setTitle("Discovered Shares");
                            final String[] arr = shares.toArray(new String[0]);
                            builder.setItems(arr, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    etRemotePath.setText(arr[which]);
                                    saveCurrentJobToMemory();
                                    saveAllToIni();
                                }
                            });
                            builder.setNegativeButton("Cancel", null);
                            builder.show();
                        }
                    });
                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        public void run() {
                            btnProbeShares.setEnabled(true);
                            appendLog("Probe failed: " + e.getMessage());
                        }
                    });
                } finally {
                    client.disconnect();
                }
            }
        }).start();
    }

    // =========================================================================
    // FOREGROUND SYNC & FOLDER CREATION
    // =========================================================================

    private void runSyncForegroundThread() {
        final String host = etHost.getText().toString().trim();
        final int port = parseIntSafe(etPort.getText().toString().trim(), spProtocol.getSelectedItemPosition() == 1 ? 21 : 445);
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        final String remotePath = etRemotePath.getText().toString().trim();
        
        final File localFolder = new File(etLocalDir.getText().toString().trim());
        final int direction = spDirection.getSelectedItemPosition();
        final boolean isFtp = spProtocol.getSelectedItemPosition() == 1;

        if (isFtp) {
            appendLog("FTP Foreground sync requires AsyncerService protocol handlers. Start background service instead.");
            return;
        }

        if (host.length() == 0 || remotePath.length() == 0) {
            appendLog("Error: Missing Host or Remote Path.");
            return;
        }

        // Feature: Automatically create local folder if it doesn't exist
        if (!localFolder.exists()) {
            if (localFolder.mkdirs()) {
                appendLog("Created local directory: " + localFolder.getAbsolutePath());
            } else {
                appendLog("Error: Could not create local directory.");
                return;
            }
        }

        appendLog("Connecting to \\\\" + host + "\\" + remotePath + " ...");
        new Thread(new Runnable() {
            public void run() {
                SmbClient client = new SmbClient();
                try {
                    SmbClient.ConnectionProfile prof = new SmbClient.ConnectionProfile();
                    prof.host = host;
                    prof.port = port;
                    prof.share = remotePath;
                    prof.username = user;
                    prof.password = pass;
                    
                    if (!client.connect(prof)) throw new Exception("Connection refused or auth failed.");
                    
                    if (direction == 0) {
                        appendLog("Syncing Local ➔ SMB Remote...");
                        client.copyLocalSubtreeToRemote(localFolder, "");
                    } else if (direction == 1) {
                        appendLog("Syncing SMB Remote ➔ Local...");
                        client.copySubtreeToLocal("", localFolder);
                    } else {
                        appendLog("Two-way foreground sync is delegated to AsyncerService background engine.");
                    }
                    appendLog("Sync completed.");
                } catch (Exception e) {
                    appendLog("Sync failed: " + e.getMessage());
                } finally {
                    client.disconnect();
                }
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
    // LUAJ SCRIPT BROWSER & RUNNER
    // =========================================================================

    public void showLuaFileBrowser(final File dir) {
        File[] files = null;
        try { files = dir.listFiles(); } catch (Throwable ignored) {}

        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        if (dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add(".. (Parent Directory)");
        }

        if (files != null) {
            for (File f : files) if (f.isDirectory()) { entries.add(f); labels.add("[" + f.getName() + "]"); }
            for (File f : files) if (!f.isDirectory() && (f.getName().toLowerCase().endsWith(".lua") || f.getName().toLowerCase().endsWith(".sh"))) {
                entries.add(f); labels.add(f.getName());
            }
        }
        labels.add("Use System File Picker...");

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(dir.getAbsolutePath());
        b.setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
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
        if (requestCode == REQ_PICK_LUA && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = uri.getPath();
                etExternalCmd.setText(path != null ? path : uri.toString());
                saveCurrentJobToMemory();
                saveAllToIni();
            }
        }
    }

    public void executeCommandOrLua(final String cmdOrPath) {
        if (cmdOrPath == null || cmdOrPath.length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (cmdOrPath.toLowerCase().endsWith(".lua")) {
                        appendLog("Running LuaJ script: " + cmdOrPath);
                        Class<?> jse = Class.forName("org.luaj.vm2.lib.jse.JsePlatform");
                        Object globals = jse.getMethod("standardGlobals").invoke(null);
                        Class<?> luaVal = Class.forName("org.luaj.vm2.LuaValue");
                        
                        luaVal.getMethod("set", String.class, String.class).invoke(globals, "SMB_HOST", etHost.getText().toString().trim());
                        luaVal.getMethod("set", String.class, String.class).invoke(globals, "SMB_SHARE", etRemotePath.getText().toString().trim());
                        
                        Object chunk = globals.getClass().getMethod("loadfile", String.class).invoke(globals, cmdOrPath);
                        Object res = luaVal.getMethod("call").invoke(chunk);
                        appendLog("LuaJ finished: " + (res != null ? res.toString() : "OK"));
                    } else {
                        appendLog("Running shell command: " + cmdOrPath);
                        Process proc = Runtime.getRuntime().exec(cmdOrPath);
                        proc.waitFor();
                        appendLog("Exit code " + proc.exitValue());
                    }
                } catch (Exception e) {
                    appendLog("Execution error: " + e.getMessage());
                }
            }
        }).start();
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
