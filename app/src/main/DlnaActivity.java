package com.example.dlnaserver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DlnaActivity extends Activity {

    private Button btnToggle, btnPickFolder;
    private EditText editPort;
    private TextView textLog, textFolder;
    private boolean isServerRunning = false;
    private String currentFolderPath;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main); 

        btnToggle = (Button) findViewById(R.id.btnToggle);
        btnPickFolder = (Button) findViewById(R.id.btnPickFolder);
        editPort = (EditText) findViewById(R.id.editPort);
        textLog = (TextView) findViewById(R.id.textLog);
        textFolder = (TextView) findViewById(R.id.textFolder);

        editPort.setText(String.valueOf(DlnaService.DEFAULT_DLNA_PORT));
        
        File extDir = Environment.getExternalStorageDirectory();
        currentFolderPath = extDir != null ? extDir.getAbsolutePath() : "/sdcard";
        textFolder.setText(currentFolderPath);

        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }

        btnPickFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFolderPicker(new File(currentFolderPath));
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(DlnaActivity.this, DlnaService.class);
                if (!isServerRunning) {
                    int port = Integer.parseInt(editPort.getText().toString());
                    serviceIntent.putExtra("PORT", port);
                    serviceIntent.putExtra("FOLDER", currentFolderPath);
                    startService(serviceIntent);
                    btnToggle.setText("Stop Server");
                    isServerRunning = true;
                } else {
                    stopService(serviceIntent);
                    btnToggle.setText("Start Server");
                    isServerRunning = false;
                }
            }
        });
    }

    private void showFolderPicker(final File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        
        File[] files = dir.listFiles();
        final List<String> dirs = new ArrayList<String>();
        dirs.add(".. (Up)");
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.isHidden()) {
                    dirs.add(f.getName());
                }
            }
        }
        Collections.sort(dirs.subList(1, dirs.size()));

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, dirs);
        new AlertDialog.Builder(this)
            .setTitle(dir.getAbsolutePath())
            .setAdapter(adapter, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        if (dir.getParentFile() != null) showFolderPicker(dir.getParentFile());
                    } else {
                        File selected = new File(dir, dirs.get(which));
                        showFolderPicker(selected);
                    }
                }
            })
            .setPositiveButton("Select This Folder", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    currentFolderPath = dir.getAbsolutePath();
                    textFolder.setText(currentFolderPath);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
