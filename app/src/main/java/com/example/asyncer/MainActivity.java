package com.example.asyncer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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

public class MainActivity extends Activity {

    private SmbClient smbClient;
    private File appIniFile;

    // Sync UI Controls
    private EditText etLocalDir;
    private Spinner spDirection;
    private Spinner spMode;
    private CheckBox cbRecursive;
    private CheckBox cbDeleteExtras;
    private TextView tvConsole;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appIniFile = new File(getFilesDir(), "asyncer.ini");

        ScrollView scroll = new ScrollView(this);
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(12, 12, 12, 12);

        // 1. Embed the SMB Connection Panel
        smbClient = new SmbClient(this, appIniFile);
        mainLayout.addView(smbClient.buildSmbPanel());

        // Visual Separator
        View sep = new View(this);
        sep.setBackgroundColor(Color.DKGRAY);
        mainLayout.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2));

        TextView lblSyncHeader = new TextView(this);
        lblSyncHeader.setText("--- Synchronization Settings ---");
        lblSyncHeader.setPadding(0, 10, 0, 10);
        mainLayout.addView(lblSyncHeader);

        // 2. Local Directory Input
        TextView lblLocal = new TextView(this);
        lblLocal.setText("Local Folder:");
        mainLayout.addView(lblLocal);

        etLocalDir = new EditText(this);
        etLocalDir.setSingleLine(true);
        File defDir = Environment.getExternalStorageDirectory();
        etLocalDir.setText(defDir != null ? defDir.getAbsolutePath() : "/sdcard");
        mainLayout.addView(etLocalDir);

        // 3. Sync Direction Spinner
        TextView lblDir = new TextView(this);
        lblDir.setText("Sync Direction:");
        mainLayout.addView(lblDir);

        spDirection = new Spinner(this);
        String[] directions = new String[] { "Local ➔ SMB Share", "SMB Share ➔ Local", "Bi-Directional (Two-Way)" };
        ArrayAdapter<String> dirAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, directions);
        dirAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDirection.setAdapter(dirAdapter);
        mainLayout.addView(spDirection);

        // 4. Sync Mode Spinner
        TextView lblMode = new TextView(this);
        lblMode.setText("File Collision Mode:");
        mainLayout.addView(lblMode);

        spMode = new Spinner(this);
        String[] modes = new String[] { "Update (Skip older/same files)", "Overwrite All", "Skip Existing" };
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(modeAdapter);
        mainLayout.addView(spMode);

        // 5. Options Checkboxes
        cbRecursive = new CheckBox(this);
        cbRecursive.setText("Include Subdirectories (Recursive)");
        cbRecursive.setChecked(true);
        mainLayout.addView(cbRecursive);

        cbDeleteExtras = new CheckBox(this);
        cbDeleteExtras.setText("Delete extra files on destination (Mirror)");
        cbDeleteExtras.setChecked(false);
        mainLayout.addView(cbDeleteExtras);

        // 6. Action Buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 10, 0, 10);

        Button btnSave = new Button(this);
        btnSave.setText("Save Settings");
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                smbClient.saveIni();
                saveSyncIni();
                appendLog("Settings saved to asyncer.ini");
                Toast.makeText(MainActivity.this, "Settings Saved", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnStartService = new Button(this);
        btnStartService.setText("Run Background Service");
        btnStartService.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                smbClient.saveIni();
                saveSyncIni();
                try {
                    Intent intent = new Intent(MainActivity.this, AsyncerService.class);
                    startService(intent);
                    appendLog("Started AsyncerService in background.");
                } catch (Exception e) {
                    appendLog("Failed to start service: " + e.getMessage());
                }
            }
        });
        btnRow.addView(btnStartService, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        mainLayout.addView(btnRow);

        Button btnSyncForeground = new Button(this);
        btnSyncForeground.setText("Sync Now (Foreground)");
        btnSyncForeground.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                smbClient.saveIni();
                saveSyncIni();
                runSyncForegroundThread();
            }
        });
        mainLayout.addView(btnSyncForeground);

        // 7. Console Log
        tvConsole = new TextView(this);
        tvConsole.setPadding(6, 12, 6, 12);
        tvConsole.setText("Asyncer UI initialized.\n");
        mainLayout.addView(tvConsole);

        scroll.addView(mainLayout);
        setContentView(scroll);

        // Load saved state
        loadSyncIni();
    }

    // =========================================================================
    // INI CONFIGURATION (Saves Local Dir, Direction, Mode, Checkboxes)
    // =========================================================================

    private void saveSyncIni() {
        if (appIniFile == null) return;
        StringBuilder sb = new StringBuilder();
        try {
            // Preserve existing lines except [SYNC] section block
            if (appIniFile.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(appIniFile));
                String line;
                boolean inSyncSection = false;
                while ((line = br.readLine()) != null) {
                    if (line.trim().equalsIgnoreCase("[SYNC]")) {
                        inSyncSection = true;
                        continue;
                    } else if (line.trim().startsWith("[")) {
                        inSyncSection = false;
                    }
                    if (!inSyncSection) {
                        sb.append(line).append("\r\n");
                    }
                }
                br.close();
            }

            // Append updated [SYNC] section
            sb.append("[SYNC]\r\n");
            sb.append("local_dir=").append(etLocalDir.getText().toString().trim()).append("\r\n");
            sb.append("direction=").append(spDirection.getSelectedItemPosition()).append("\r\n");
            sb.append("mode=").append(spMode.getSelectedItemPosition()).append("\r\n");
            sb.append("recursive=").append(cbRecursive.isChecked() ? "1" : "0").append("\r\n");
            sb.append("delete_extras=").append(cbDeleteExtras.isChecked() ? "1" : "0").append("\r\n");

            FileOutputStream fos = new FileOutputStream(appIniFile, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            appendLog("Error saving sync ini: " + e.getMessage());
        }
    }

    private void loadSyncIni() {
        if (appIniFile == null || !appIniFile.exists()) return;
        try {
            BufferedReader br = new BufferedReader(new FileReader(appIniFile));
            String line;
            boolean inSyncSection = false;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.equalsIgnoreCase("[SYNC]")) {
                    inSyncSection = true;
                    continue;
                } else if (line.startsWith("[")) {
                    inSyncSection = false;
                }
                if (inSyncSection && line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    String k = parts[0].trim().toLowerCase();
                    String v = parts[1].trim();

                    if (k.equals("local_dir")) etLocalDir.setText(v);
                    else if (k.equals("direction")) spDirection.setSelection(parseIntSafe(v, 0));
                    else if (k.equals("mode")) spMode.setSelection(parseIntSafe(v, 0));
                    else if (k.equals("recursive")) cbRecursive.setChecked("1".equals(v) || "true".equalsIgnoreCase(v));
                    else if (k.equals("delete_extras")) cbDeleteExtras.setChecked("1".equals(v) || "true".equalsIgnoreCase(v));
                }
            }
            br.close();
        } catch (Exception ignored) {}
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    // =========================================================================
    // FOREGROUND TEST SYNC (Uses UI Options)
    // =========================================================================

    private void runSyncForegroundThread() {
        final String host = smbClient.etHost.getText().toString().trim();
        final int port = parseIntSafe(smbClient.etPort.getText().toString().trim(), 445);
        final String user = smbClient.etUser.getText().toString().trim();
        final String pass = smbClient.etPass.getText().toString();
        final String share = smbClient.getSelectedShare();
        final File localFolder = new File(etLocalDir.getText().toString().trim());

        final int direction = spDirection.getSelectedItemPosition(); // 0=L2S, 1=S2L, 2=Bi
        final boolean recursive = cbRecursive.isChecked();

        if (share.length() == 0) {
            appendLog("Error: No SMB share selected.");
            return;
        }

        appendLog("Connecting to \\\\" + host + "\\" + share + " ...");
        new Thread(new Runnable() {
            public void run() {
                SmbClient client = new SmbClient();
                try {
                    SmbClient.ConnectionProfile prof = new SmbClient.ConnectionProfile();
                    prof.host = host;
                    prof.port = port;
                    prof.share = share;
                    prof.username = user;
                    prof.password = pass;
                    
                    if (!client.connect(prof)) {
                        throw new Exception("Connection failed (Authentication or Network error).");
                    }
                    
                    if (direction == 0) {
                        appendLog("Syncing Local -> SMB");
                        client.copyLocalSubtreeToRemote(localFolder, "");
                    } else if (direction == 1) {
                        appendLog("Syncing SMB -> Local");
                        client.copySubtreeToLocal("", localFolder);
                    } else {
                        appendLog("Two-way sync initiated (delegating to AsyncerService)...");
                        // Add bidirectional logic here or leave to AsyncerService
                    }

                    appendLog("Sync completed successfully.");
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
                // Scroll to bottom (optional)
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (smbClient != null) {
            smbClient.onActivityResult(requestCode, resultCode, data);
        }
    }
}
