package com.example.asyncer;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private Map<String, Map<String, String>> iniData;

    // Jobs UI
    private Spinner spnJobs;
    private EditText txtJobName, txtSrc, txtDst, txtFlags, txtMasks, txtCmd, txtIfProc, txtIfNotProc;
    private List<String> jobNames = new ArrayList<String>();
    private ArrayAdapter<String> jobAdapter;

    // Global Settings UI
    private EditText txtSetLog, txtSetDisk, txtSetRefresh;

    // SMB Credentials UI
    private Spinner spnCreds;
    private EditText txtCredShare, txtCredUser, txtCredPass;
    private List<String> shareNames = new ArrayList<String>();
    private ArrayAdapter<String> credAdapter;

    private Button btnPause;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
            }, 1);
        }

        iniData = AsyncerService.readIni(AsyncerService.getIniFile(this));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        scroll.addView(root);

        // --- Top Control Bar (Tray Menu Equivalent) ---
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
        txtSrc = addLabeledInput(root, "Src (Local, \\\\IP\\Share\\Dir, or ftp://):", "/sdcard/Documents");
        txtDst = addLabeledInput(root, "Dst (Local, \\\\IP\\Share\\Dir, or ftp://):", "\\\\192.168.1.100\\shared\\Backup");
        txtFlags = addLabeledInput(root, "Flags (1=Newer,2=Old,4=Lrg,8=Sml,16=All,64=Mv,128=Bak,256=Time):", "1");
        txtMasks = addLabeledInput(root, "Masks (*.* or *.jpg;*.pdf):", "*.*");
        txtCmd = addLabeledInput(root, "Post-Sync Cmd:", "");
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
        // 3. SMB Credentials Section
        // =====================================================================
        addHeader(root, "--- SMB Credentials ---");
        spnCreds = new Spinner(this);
        credAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, shareNames);
        credAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnCreds.setAdapter(credAdapter);
        root.addView(spnCreds);

        txtCredShare = addLabeledInput(root, "Share (\\\\Server\\Share):", "\\\\192.168.1.100\\shared");
        txtCredUser = addLabeledInput(root, "User (or DOMAIN\\User):", "");
        txtCredPass = addLabeledInput(root, "Pass:", "");

        LinearLayout credBtns = new LinearLayout(this);
        credBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSaveCred = new Button(this);
        btnSaveCred.setText("Save Cred");
        btnSaveCred.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCredential();
            }
        });
        Button btnDelCred = new Button(this);
        btnDelCred.setText("Delete Cred");
        btnDelCred.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteCredential();
            }
        });
        credBtns.addView(btnSaveCred);
        credBtns.addView(btnDelCred);
        root.addView(credBtns);

        setContentView(scroll);

        setupSpinners();
        refreshJobSpinner();
        refreshCredSpinner();
        restartOrSignalService(null);
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

    private void refreshJobSpinner() {
        jobNames.clear();
        jobNames.addAll(iniData.get("Jobs").keySet());
        jobAdapter.notifyDataSetChanged();
    }

    private void refreshCredSpinner() {
        shareNames.clear();
        shareNames.addAll(iniData.get("Credentials").keySet());
        credAdapter.notifyDataSetChanged();
    }

    private void saveJob() {
        String name = txtJobName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a Job Name.", Toast.LENGTH_SHORT).show();
            return;
        }
        String fullJob = String.format("%s,%s,%s,%s,%s,%s,%s",
                txtSrc.getText().toString().trim(),
                txtDst.getText().toString().trim(),
                txtFlags.getText().toString().trim(),
                txtMasks.getText().toString().trim(),
                txtCmd.getText().toString().trim(),
                txtIfProc.getText().toString().trim(),
                txtIfNotProc.getText().toString().trim());
        iniData.get("Jobs").put(name, fullJob);
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        refreshJobSpinner();
        Toast.makeText(this, "Job saved.", Toast.LENGTH_SHORT).show();
    }

    private void deleteJob() {
        String name = txtJobName.getText().toString().trim();
        if (name.isEmpty() || !iniData.get("Jobs").containsKey(name)) {
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
        String share = txtCredShare.getText().toString().trim();
        if (share.isEmpty()) {
            Toast.makeText(this, "Please provide a Share path.", Toast.LENGTH_SHORT).show();
            return;
        }
        String fullCred = txtCredUser.getText().toString().trim() + "," + txtCredPass.getText().toString().trim();
        iniData.get("Credentials").put(share, fullCred);
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        refreshCredSpinner();
        Toast.makeText(this, "Credentials saved.", Toast.LENGTH_SHORT).show();
    }

    private void deleteCredential() {
        String share = txtCredShare.getText().toString().trim();
        if (share.isEmpty() || !iniData.get("Credentials").containsKey(share)) {
            Toast.makeText(this, "Select a valid Share to delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        iniData.get("Credentials").remove(share);
        AsyncerService.writeIni(AsyncerService.getIniFile(this), iniData);
        txtCredShare.setText(""); txtCredUser.setText(""); txtCredPass.setText("");
        refreshCredSpinner();
        Toast.makeText(this, "Credential deleted.", Toast.LENGTH_SHORT).show();
    }

    private void restartOrSignalService(String action) {
        Intent serviceIntent = new Intent(this, AsyncerService.class);
        if (action != null) serviceIntent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    private void addHeader(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText("\n" + title);
        tv.setTextSize(16);
        parent.addView(tv);
    }

    private EditText addLabeledInput(LinearLayout parent, String label, String defVal) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setText(defVal);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }
}
