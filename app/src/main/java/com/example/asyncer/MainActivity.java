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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    public static final int REQ_PICK_LUA = 9001;

    private File iniFile;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Map<String, String>> jobsData = new HashMap<Integer, Map<String, String>>();
    private int currentJobIndex = 0;
    private boolean isUpdatingUI = false;

    // Global Options
    private CheckBox cbRunOnStart;
    private Spinner spJobSelect;

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
        iniFile = new File(getFilesDir(), "asyncer.ini");

        // Ensure default /sdcard/sync folder exists on launch
        File defaultSyncFolder = new File(Environment.getExternalStorageDirectory(), "sync");
        if (!defaultSyncFolder.exists()) {
            defaultSyncFolder.mkdirs();
        }

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

        // Share / Remote Folder Dropdown + Test/Probe Button
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
        lblCmd.setText("Run LuaJ Script (.lua) or External Command:");
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
                if (!startDir.exists()) startDir = Environment.getExternalStorageDirectory();
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
        tvConsole.setText("Asyncer ready.\n");
        root.addView(tvConsole);

        scroll.addView(root);
        setContentView(scroll);

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

        if (savedInstanceState == null && cbRunOnStart.isChecked()) {
            appendLog("Run on start enabled. Launching AsyncerService...");
            try {
                BootReceiver.startServiceCompat(this, new Intent(this, AsyncerService.class));
            } catch (Exception ignored) {}
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

    private String getEffectiveRemotePath() {
        String override = etRemoteSubPath.getText().toString().trim();
        if (override.length() > 0) return override;
        Object sel = spShares.getSelectedItem();
        return sel != null ? sel.toString().trim() : "";
    }

    // =========================================================================
    // INI PERSISTENCE (Saves Shares Dropdown List + All 5 Jobs + Run on Start)
    // =========================================================================

    private void loadAllFromIni() {
        if (!iniFile.exists()) {
            loadJobToUI(0);
            return;
        }
        BufferedReader br = null;
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
                } else if (currentMap != null) {
                    currentMap.put(k, v);
                }
            }
        } catch (Exception e) {
            appendLog("Failed to load INI: " + e.getMessage());
        } finally {
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

        String defSync = new File(Environment.getExternalStorageDirectory(), "sync").getAbsolutePath();
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
        if (!localFolder.exists()) localFolder.mkdirs();

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
    // TEST / PROBE BUTTON (Probes SMB Shares or FTP Directories + Tooltip Dialog)
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
                            // Also list files inside the first share if available
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
                            appendLog("Probe succeeded: " + discovered.size() + " share(s)/folder(s) saved to INI.");

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
    // FOREGROUND SYNC (Supports Both SMB and FTP)
    // =========================================================================

    private void runSyncForegroundThread() {
        final int proto = spProtocol.getSelectedItemPosition(); // 0=SMB, 1=FTP
        final String host = etHost.getText().toString().trim();
        final int port = parseIntSafe(etPort.getText().toString().trim(), proto == 1 ? 21 : 445);
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        final String remotePath = getEffectiveRemotePath();
        final File localFolder = new File(etLocalDir.getText().toString().trim());
        final int direction = spDirection.getSelectedItemPosition();
        final boolean recursive = cbRecursive.isChecked();

        if (host.length() == 0 || (proto == 0 && remotePath.length() == 0)) {
            appendLog("Error: Missing Host or Share.");
            return;
        }

        if (!localFolder.exists()) {
            localFolder.mkdirs();
            appendLog("Created local folder: " + localFolder.getAbsolutePath());
        }

        new Thread(new Runnable() {
            public void run() {
                if (proto == 1) {
                    FtpClient ftp = new FtpClient();
                    try {
                        String ftpPath = remotePath.length() > 0 ? remotePath : "/";
                        appendLog("Connecting to FTP " + host + ":" + port + " (" + ftpPath + ")...");
                        if (!ftp.connect(host, port, user, pass)) {
                            throw new Exception("FTP connect/login failed");
                        }
                        if (direction == 0) {
                            appendLog("Uploading Local -> FTP...");
                            ftp.copyLocalSubtreeToRemote(localFolder, ftpPath, recursive);
                        } else if (direction == 1) {
                            appendLog("Downloading FTP -> Local...");
                            ftp.copySubtreeToLocal(ftpPath, localFolder, recursive);
                        } else {
                            appendLog("Bi-Directional FTP sync...");
                            ftp.copyLocalSubtreeToRemote(localFolder, ftpPath, recursive);
                            ftp.copySubtreeToLocal(ftpPath, localFolder, recursive);
                        }
                        appendLog("FTP Sync complete.");
                    } catch (Exception e) {
                        appendLog("FTP Sync failed: " + e.getMessage());
                    } finally {
                        ftp.disconnect();
                    }
                } else {
                    SmbClient smb = new SmbClient();
                    try {
                        appendLog("Connecting to SMB \\\\" + host + "\\" + remotePath + "...");
                        SmbClient.ConnectionProfile prof = new SmbClient.ConnectionProfile();
                        prof.host = host;
                        prof.port = port;
                        prof.share = remotePath;
                        prof.username = user;
                        prof.password = pass;
                        if (!smb.connect(prof)) {
                            throw new Exception("SMB connect/auth failed");
                        }
                        if (direction == 0) {
                            appendLog("Uploading Local -> SMB...");
                            smb.copyLocalSubtreeToRemote(localFolder, "");
                        } else if (direction == 1) {
                            appendLog("Downloading SMB -> Local...");
                            smb.copySubtreeToLocal("", localFolder);
                        } else {
                            appendLog("Bi-Directional SMB sync...");
                            smb.copyLocalSubtreeToRemote(localFolder, "");
                            smb.copySubtreeToLocal("", localFolder);
                        }
                        appendLog("SMB Sync complete.");
                    } catch (Exception e) {
                        appendLog("SMB Sync failed: " + e.getMessage());
                    } finally {
                        smb.disconnect();
                    }
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

                        luaVal.getMethod("set", String.class, String.class)
                                .invoke(globals, "SMB_HOST", etHost.getText().toString().trim());
                        luaVal.getMethod("set", String.class, String.class)
                                .invoke(globals, "SMB_SHARE", getEffectiveRemotePath());

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
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
