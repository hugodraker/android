package com.example.asyncer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private Map<String, Map<String, String>> iniData;

    // Jobs UI
    private Spinner spnJobs, spnSrcShares, spnDstShares;
    private EditText txtJobName, txtSrc, txtDst, txtFlags, txtMasks, txtCmd, txtIfProc, txtIfNotProc;
    private List<String> jobNames = new ArrayList<String>();
    private ArrayAdapter<String> jobAdapter;

    // Shared dropdown list for Src, Dst, and Credentials
    private List<String> shareNames = new ArrayList<String>();
    private List<String> jobShareChoices = new ArrayList<String>();
    private ArrayAdapter<String> credAdapter;
    private ArrayAdapter<String> srcShareAdapter;
    private ArrayAdapter<String> dstShareAdapter;

    // Global Settings UI
    private EditText txtSetLog, txtSetDisk, txtSetRefresh;

    // SMB Credentials UI
    private Spinner spnCreds;
    private EditText txtCredShare, txtCredUser, txtCredPass;

    private Button btnPause, btnTestSmb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestStoragePermissionsCompat();

        iniData = AsyncerService.readIni(AsyncerService.getIniFile(this));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        scroll.addView(root);

        // --- Top Control Bar ---
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);

        btnPause = new Button(this);
        btnPause.setText(AsyncerService.g_Paused ? "Resume Sync" : "Pause Sync");
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AsyncerService.g_Paused = !AsyncerService.g_Paused;
                btnPause.setText(AsyncerService.g_Paused ? "Resume Sync" : "Pause Sync");
                restartOrSignalService(null);
            }
        });
        topBar.addView(btnPause);

        Button btnCaptive = new Button(this);
        btnCaptive.setText("Captive Portal Check");
        btnCaptive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartOrSignalService("CHECK_CAPTIVE");
                Toast.makeText(MainActivity.this, "Checking Captive Portal...", Toast.LENGTH_SHORT).show();
            }
        });
        topBar.addView(btnCaptive);
        root.addView(topBar);

        // =====================================================================
        // 1. Job Configuration Section
        // =====================================================================
        addHeader(root, "--- Job Configuration ---");
        spnJobs = new Spinner(this);
        jobAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, jobNames);
        jobAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnJobs.setAdapter(jobAdapter);
        root.addView(spnJobs);

        txtJobName = addLabeledInput(root, "Job Name:", "BackupJob");

        addLabel(root, "Src Share Preset (optional):");
        spnSrcShares = new Spinner(this);
        srcShareAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, jobShareChoices);
        srcShareAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnSrcShares.setAdapter(srcShareAdapter);
        root.addView(spnSrcShares);

        txtSrc = addLabeledInput(root, "Src (Local, \\\\IP\\Share\\Dir, or ftp://):", "/sdcard/Documents");

        addLabel(root, "Dst Share Preset (optional):");
        spnDstShares = new Spinner(this);
        dstShareAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, jobShareChoices);
        dstShareAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnDstShares.setAdapter(dstShareAdapter);
        root.addView(spnDstShares);

        txtDst = addLabeledInput(root, "Dst (Local, \\\\IP\\Share\\Dir, or ftp://):", "\\\\192.168.1.100\\shared\\Backup");
        txtFlags = addLabeledInput(root, "Flags (1=Newer,2=Old,4=Lrg,8=Sml,16=All,64=Mv,128=Bak,256=Time):", "1");
        txtMasks = addLabeledInput(root, "Masks (*.* or *.jpg;*.pdf):", "*.*");

        addLabel(root, "Post-Sync Cmd (Shell cmd or .lua script path):");
        LinearLayout cmdRow = new LinearLayout(this);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        txtCmd = new EditText(this);
        txtCmd.setSingleLine(true);
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        txtCmd.setLayoutParams(cmdLp);
        cmdRow.addView(txtCmd);

        Button btnBrowseLua = new Button(this);
        btnBrowseLua.setText("Browse .lua");
        btnBrowseLua.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File startDir = Environment.getExternalStorageDirectory();
                if (startDir == null || !startDir.exists()) startDir = new File("/");
                showLuaFileBrowser(startDir);
            }
        });
        cmdRow.addView(btnBrowseLua);

        Button btnRunLua = new Button(this);
        btnRunLua.setText("Run Now");
        btnRunLua.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String cmd = txtCmd.getText().toString().trim();
                if (cmd.length() == 0) {
                    Toast.makeText(MainActivity.this, "Enter or browse a command/.lua script first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = AsyncerService.executeCommandOrLua(cmd);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, res, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        });
        cmdRow.addView(btnRunLua);
        root.addView(cmdRow);

        txtIfProc = addLabeledInput(root, "IfProcExists:", "");
        txtIfNotProc = addLabeledInput(root, "IfProcNotExists:", "");

        LinearLayout jobBtns = new LinearLayout(this);
        jobBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSaveJob = new Button(this);
        btnSaveJob.setText("Save Job");
        btnSaveJob.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveJob();
            }
        });
        Button btnDelJob = new Button(this);
        btnDelJob.setText("Delete Job");
        btnDelJob.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteJob();
            }
        });
        jobBtns.addView(btnSaveJob);
        jobBtns.addView(btnDelJob);
        root.addView(jobBtns);

        // =====================================================================
        // 2. Global Settings Section
        // =====================================================================
        addHeader(root, "--- Global Settings ---");
        Map<String, String> settings = iniData.get("Settings");
        txtSetLog = addLabeledInput(root, "LogFile:", settings.get("LogFile") != null ? settings.get("LogFile") : "/sdcard/asyncer.log");
        txtSetDisk = addLabeledInput(root, "MaxDiskMBps (0 = unlimited):", settings.get("MaxDiskMBps") != null ? settings.get("MaxDiskMBps") : "0");
        txtSetRefresh = addLabeledInput(root, "RefreshInterval (seconds):", settings.get("RefreshInterval") != null ? settings.get("RefreshInterval") : "3600");

        Button btnSaveSet = new Button(this);
        btnSaveSet.setText("Save Settings");
        btnSaveSet.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, String> s = iniData.get("Settings");
                s.put("LogFile", txtSetLog.getText().toString().trim());
                s.put("MaxDiskMBps", txtSetDisk.getText().toString().trim());
                s.put("RefreshInterval", txtSetRefresh.getText().toString().trim());
                AsyncerService.writeIni(AsyncerService.getIniFile(MainActivity.this), iniData);
                Toast.makeText(MainActivity.this, "Settings saved.", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnSaveSet);

        // =====================================================================
        // 3. SMB Shares & Credentials Section
        // =====================================================================
        addHeader(root, "--- SMB Shares & Credentials ---");
        addLabel(root, "Saved Shares Dropdown:");
        spnCreds = new Spinner(this);
        credAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, shareNames);
        credAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnCreds.setAdapter(credAdapter);
        root.addView(spnCreds);

        txtCredShare = addLabeledInput(root, "Server or Share (\\\\Server or \\\\Server\\Share):", "\\\\192.168.1.100\\shared");
        txtCredUser = addLabeledInput(root, "User (or DOMAIN\\User):", "");
        txtCredPass = addLabeledInput(root, "Pass:", "");

        LinearLayout credBtns = new LinearLayout(this);
        credBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSaveCred = new Button(this);
        btnSaveCred.setText("Save Share/Cred");
        btnSaveCred.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCredential();
            }
        });
        Button btnDelCred = new Button(this);
        btnDelCred.setText("Delete Share");
        btnDelCred.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteCredential();
            }
        });
        btnTestSmb = new Button(this);
        btnTestSmb.setText("Test & Probe Shares");
        btnTestSmb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAndProbeSmb();
            }
        });
        credBtns.addView(btnSaveCred);
        credBtns.addView(btnDelCred);
        credBtns.addView(btnTestSmb);
        root.addView(credBtns);

        setContentView(scroll);

        setupSpinners();
        refreshJobSpinner();
        refreshCredSpinner();
        restartOrSignalService(null);
    }

    private void requestStoragePermissionsCompat() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Method m = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                m.invoke(this, new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.POST_NOTIFICATIONS"
                }, 1);
            } catch ( Throwable ignored ) {}
        }
    }

    private void setupSpinners() {
        spnJobs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String jName = jobNames.get(position);
                txtJobName.setText(jName);
                String raw = iniData.get("Jobs").get(jName);
                if (raw != null) {
                    String[] tok = raw.split(",", -1);
                    txtSrc.setText(tok.length > 0 ? tok[0] : "");
                    txtDst.setText(tok.length > 1 ? tok[1] : "");
                    txtFlags.setText(tok.length > 2 ? tok[2] : "");
                    txtMasks.setText(tok.length > 3 ? tok[3] : "");
                    txtCmd.setText(tok.length > 4 ? tok[4] : "");
                    txtIfProc.setText(tok.length > 5 ? tok[5] : "");
                    txtIfNotProc.setText(tok.length > 6 ? tok[6] : "");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spnSrcShares.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position <= 0) return;
                String chosenShare = jobShareChoices.get(position);
                applyShareToPathInput(txtSrc, chosenShare);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spnDstShares.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position <= 0) return;
                String chosenShare = jobShareChoices.get(position);
                applyShareToPathInput(txtDst, chosenShare);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spnCreds.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String sName = shareNames.get(position);
                txtCredShare.setText(sName);
                String raw = iniData.get("Credentials").get(sName);
                if (raw != null) {
                    int comma = raw.indexOf(',');
                    if (comma >= 0) {
                        txtCredUser.setText(raw.substring(0, comma));
                        txtCredPass.setText(raw.substring(comma + 1));
                    } else {
                        txtCredUser.setText(raw);
                        txtCredPass.setText("");
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void applyShareToPathInput(EditText targetInput, String shareRoot) {
        String current = targetInput.getText().toString().trim();
        if (current.startsWith("\\\\")) {
            AsyncerService.UncPath u = AsyncerService.parseUncPath(current);
            if (u != null && u.relPath != null && u.relPath.length() > 0) {
                targetInput.setText(shareRoot + "\\" + u.relPath);
                return;
            }
        }
        targetInput.setText(shareRoot + "\\");
    }

    private void refreshJobSpinner() {
        jobNames.clear();
        jobNames.addAll(iniData.get("Jobs").keySet());
        Collections.sort(jobNames);
        jobAdapter.notifyDataSetChanged();
    }

    private void refreshCredSpinner() {
        shareNames.clear();
        Map<String, String> creds = iniData.get("Credentials");
        Map<String, String> sharesSec = iniData.get("Shares");

        for (String k : creds.keySet()) {
            if (!shareNames.contains(k)) shareNames.add(k);
            sharesSec.put(k, "1");
        }
        for (String k : sharesSec.keySet()) {
            if (!shareNames.contains(k)) shareNames.add(k);
        }
        Collections.sort(shareNames, String.CASE_INSENSITIVE_ORDER);
        credAdapter.notifyDataSetChanged();

        jobShareChoices.clear();
        jobShareChoices.add("-- Select Saved Share --");
        jobShareChoices.addAll(shareNames);
        srcShareAdapter.notifyDataSetChanged();
        dstShareAdapter.notifyDataSetChanged();
    }

    private void saveJob() {
        String name = txtJobName.getText().toString().trim();
        if (name.length() == 0) {
            Toast.makeText(this, "Please enter a Job Name.", Toast.LENGTH_SHORT).show();
            return;
        }
        String src = txtSrc.getText().toString().trim();
        String dst = txtDst.getText().toString().trim();

        // Automatically record any UNC share used in Src or Dst into [Shares]
        AsyncerService.UncPath uSrc = AsyncerService.parseUncPath(src);
        if (uSrc != null && uSrc.share.length() > 0) {
            iniData.get("Shares").put(uSrc.shareKey, "1");
        }
        AsyncerService.UncPath uDst = AsyncerService.parseUncPath(dst);
        if (uDst != null && uDst.share.length() > 0) {
            iniData.get("Shares").put(uDst.shareKey, "1");
        }

        String fullJob = String.format("%s,%s,%s,%s,%s,%s,%s",
                src,
                dst,
                txtFlags.getText().toString().trim(),
                txtMasks.getText().toString().trim(),
                txtCmd.getText().toString().trim(),
                txtIfProc.getText().toString().trim(),
                txtIfNotProc.getText().toString().trim());
        iniData.get("Jobs").put(name, fullJob);
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        refreshJobSpinner();
        refreshCredSpinner();
        Toast.makeText(this, "Job saved.", Toast.LENGTH_SHORT).show();
    }

    private void deleteJob() {
        String name = txtJobName.getText().toString().trim();
        if (name.length() == 0 || !iniData.get("Jobs").containsKey(name)) {
            Toast.makeText(this, "Select a valid Job Name to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        iniData.get("Jobs").remove(name);
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        txtJobName.setText(""); txtSrc.setText(""); txtDst.setText("");
        txtFlags.setText(""); txtMasks.setText(""); txtCmd.setText("");
        txtIfProc.setText(""); txtIfNotProc.setText("");
        refreshJobSpinner();
        Toast.makeText(this, "Job deleted.", Toast.LENGTH_SHORT).show();
    }

    private void saveCredential() {
        String share = normalizeShareKey(txtCredShare.getText().toString().trim());
        if (share.length() == 0) {
            Toast.makeText(this, "Please provide a Share path.", Toast.LENGTH_SHORT).show();
            return;
        }
        txtCredShare.setText(share);
        String fullCred = txtCredUser.getText().toString().trim() + "," + txtCredPass.getText().toString().trim();
        iniData.get("Credentials").put(share, fullCred);
        iniData.get("Shares").put(share, "1");
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        refreshCredSpinner();
        selectShareInCredSpinner(share);
        Toast.makeText(this, "Share & Credentials saved.", Toast.LENGTH_SHORT).show();
    }

    private void deleteCredential() {
        String share = normalizeShareKey(txtCredShare.getText().toString().trim());
        if (share.length() == 0 || (!iniData.get("Credentials").containsKey(share) && !iniData.get("Shares").containsKey(share))) {
            Toast.makeText(this, "Select a valid Share to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        iniData.get("Credentials").remove(share);
        iniData.get("Shares").remove(share);
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        txtCredShare.setText(""); txtCredUser.setText(""); txtCredPass.setText("");
        refreshCredSpinner();
        Toast.makeText(this, "Share deleted.", Toast.LENGTH_SHORT).show();
    }

    private static String normalizeShareKey(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().replace('/', '\\');
        if (clean.length() == 0) return "";
        while (clean.startsWith("\\")) clean = clean.substring(1);
        while (clean.endsWith("\\")) clean = clean.substring(0, clean.length() - 1);
        if (clean.length() == 0) return "";
        return "\\\\" + clean;
    }

    private void selectShareInCredSpinner(String share) {
        int idx = shareNames.indexOf(share);
        if (idx >= 0) {
            spnCreds.setSelection(idx);
        }
    }

    private void testAndProbeSmb() {
        final String rawTarget = txtCredShare.getText().toString().trim();
        final String user = txtCredUser.getText().toString().trim();
        final String pass = txtCredPass.getText().toString().trim();

        if (rawTarget.length() == 0) {
            Toast.makeText(this, "Enter \\\\Server or \\\\Server\\Share first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String clean = rawTarget.replace('/', '\\');
        while (clean.startsWith("\\")) clean = clean.substring(1);
        int slash = clean.indexOf('\\');
        final String server = (slash >= 0) ? clean.substring(0, slash) : clean;
        final String specificShare = (slash >= 0) ? clean.substring(slash + 1) : "";

        btnTestSmb.setEnabled(false);
        btnTestSmb.setText("Probing...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                SmbClient client = new SmbClient();
                final List<String> discovered = client.probeServerShares(server, user, pass);
                boolean directConnectOk = false;

                if (specificShare.length() > 0) {
                    SmbClient.ConnectionProfile p = new SmbClient.ConnectionProfile();
                    p.server = server;
                    p.share = specificShare;
                    p.user = user;
                    p.pass = pass;
                    if (client.connectServer(p)) {
                        directConnectOk = true;
                        String cleanShareName = specificShare;
                        int subSlash = cleanShareName.indexOf('\\');
                        if (subSlash > 0) cleanShareName = cleanShareName.substring(0, subSlash);
                        if (!discovered.contains(cleanShareName)) {
                            discovered.add(cleanShareName);
                        }
                        client.disconnectAll();
                    }
                }

                final boolean finalDirectOk = directConnectOk;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnTestSmb.setEnabled(true);
                        btnTestSmb.setText("Test & Probe Shares");

                        if (discovered.size() == 0 && !finalDirectOk) {
                            Toast.makeText(MainActivity.this,
                                    "SMB Test Failed: Could not authenticate or list shares on " + server,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }

                        String credVal = user + "," + pass;
                        String firstAdded = null;
                        for (int i = 0; i < discovered.size(); i++) {
                            String sName = discovered.get(i);
                            String fullShareKey = "\\\\" + server + "\\" + sName;
                            iniData.get("Shares").put(fullShareKey, "1");
                            iniData.get("Credentials").put(fullShareKey, credVal);
                            if (firstAdded == null) firstAdded = fullShareKey;
                        }

                        AsyncerService.writeIni(AsyncerService.getIniFile(MainActivity.this), iniData);
                        refreshCredSpinner();
                        if (firstAdded != null) {
                            txtCredShare.setText(firstAdded);
                            selectShareInCredSpinner(firstAdded);
                        }

                        Toast.makeText(MainActivity.this,
                                "Connected to " + server + "! Added " + discovered.size() + " share(s) to dropdown.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void showLuaFileBrowser(final File dir) {
        File[] files = dir.listFiles();
        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        if (dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add("[..] (Parent Directory)");
        }

        if (files != null) {
            List<File> fileList = new ArrayList<File>();
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f.isDirectory() || f.getName().toLowerCase().endsWith(".lua") || f.getName().toLowerCase().endsWith(".sh")) {
                    fileList.add(f);
                }
            }
            Collections.sort(fileList, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (int i = 0; i < fileList.size(); i++) {
                File f = fileList.get(i);
                entries.add(f);
                labels.add(f.isDirectory() ? "[" + f.getName() + "]" : f.getName());
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(dir.getAbsolutePath());
        builder.setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                File selected = entries.get(which);
                if (selected.isDirectory()) {
                    showLuaFileBrowser(selected);
                } else {
                    txtCmd.setText(selected.getAbsolutePath());
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void restartOrSignalService(String action) {
        Intent serviceIntent = new Intent(this, AsyncerService.class);
        if (action != null) serviceIntent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method m = getClass().getMethod("startForegroundService", Intent.class);
                m.invoke(this, serviceIntent);
                return;
            } catch (Throwable ignored) {}
        }
        startService(serviceIntent);
    }

    private void addHeader(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText("\n" + title);
        tv.setTextSize(16);
        parent.addView(tv);
    }

    private void addLabel(LinearLayout parent, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
    }

    private EditText addLabeledInput(LinearLayout parent, String label, String defVal) {
        addLabel(parent, label);
        EditText et = new EditText(this);
        et.setText(defVal);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }
}
