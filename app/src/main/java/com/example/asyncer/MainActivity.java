package com.example.asyncer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

public class MainActivity extends Activity {

    private SmbClientModule smbModule;
    private EditText etLocalDir;
    private TextView tvConsole;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(10, 10, 10, 10);

        File iniFile = new File(getFilesDir(), "asyncer.ini");
        smbModule = new SmbClientModule(this, iniFile);
        mainLayout.addView(smbModule.buildSmbPanel());

        // Local Folder Row
        TextView lblLocal = new TextView(this);
        lblLocal.setText("Local Folder to Sync:");
        mainLayout.addView(lblLocal);

        etLocalDir = new EditText(this);
        etLocalDir.setSingleLine(true);
        File defDir = Environment.getExternalStorageDirectory();
        etLocalDir.setText(defDir != null ? defDir.getAbsolutePath() : "/sdcard");
        mainLayout.addView(etLocalDir);

        // Action Buttons Row
        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnSyncUpload = new Button(this);
        btnSyncUpload.setText("Sync Local -> SMB Share");
        btnSyncUpload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                smbModule.saveIni();
                runSyncLocalToSmb();
            }
        });
        syncRow.addView(btnSyncUpload, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnSaveIni = new Button(this);
        btnSaveIni.setText("Save INI");
        btnSaveIni.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                smbModule.saveIni();
                appendLog("Saved configuration and share list to asyncer.ini");
            }
        });
        syncRow.addView(btnSaveIni);

        mainLayout.addView(syncRow);

        tvConsole = new TextView(this);
        tvConsole.setPadding(6, 12, 6, 12);
        tvConsole.setText("Asyncer ready.\n");
        mainLayout.addView(tvConsole);

        scroll.addView(mainLayout);
        setContentView(scroll);
    }

    private void runSyncLocalToSmb() {
        final String host = smbModule.etHost.getText().toString().trim();
        final int port;
        int p = 445;
        try { p = Integer.parseInt(smbModule.etPort.getText().toString().trim()); } catch (Exception ignored) {}
        port = p;
        final String user = smbModule.etUser.getText().toString().trim();
        final String pass = smbModule.etPass.getText().toString();
        final String share = smbModule.getSelectedShare();
        final File localFolder = new File(etLocalDir.getText().toString().trim());

        if (share.length() == 0) {
            appendLog("Error: No SMB share selected.");
            return;
        }
        if (!localFolder.exists() || !localFolder.isDirectory()) {
            appendLog("Error: Local folder does not exist: " + localFolder.getAbsolutePath());
            return;
        }

        appendLog("Connecting to \\\\" + host + "\\" + share + " ...");
        new Thread(new Runnable() {
            public void run() {
                SmbClientModule.SmbClientSession session =
                        new SmbClientModule.SmbClientSession(host, port, user, pass);
                try {
                    session.connectShare(share);
                    File[] files = localFolder.listFiles();
                    int uploaded = 0;
                    if (files != null) {
                        for (int i = 0; i < files.length; i++) {
                            File f = files[i];
                            if (f.isFile() && f.canRead()) {
                                appendLog("Uploading " + f.getName() + " (" + f.length() + " bytes)...");
                                session.uploadFile(f.getName(), f);
                                uploaded++;
                            }
                        }
                    }
                    appendLog("Sync complete. Uploaded " + uploaded + " file(s) to " + share + ".");
                } catch (Exception e) {
                    appendLog("Sync failed: " + e.getMessage());
                } finally {
                    session.close();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (smbModule != null) {
            smbModule.onActivityResult(requestCode, resultCode, data);
        }
    }
}
