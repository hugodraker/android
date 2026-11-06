package com.example.dlnaserver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DlnaActivity extends Activity {

    private Button btnToggle, btnAddFolder;
    private EditText editPort;
    private TextView textStatus;
    private ListView listFolders;
    private ArrayAdapter<String> folderAdapter;
    private ArrayList<String> sharedFolders = new ArrayList<String>();
    private boolean isServerRunning = false;
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("DlnaPrefs", MODE_PRIVATE);
        loadSettings();

        // Build the entire UI programmatically (Zero XML dependency)
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.WHITE);

        // Title
        TextView title = new TextView(this);
        title.setText("DLNA Media Server");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // Port Row
        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblPort = new TextView(this);
        lblPort.setText("Server Port: ");
        lblPort.setTextColor(Color.BLACK);
        lblPort.setTextSize(16);
        portRow.addView(lblPort);

        editPort = new EditText(this);
        editPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        editPort.setText(String.valueOf(prefs.getInt("PORT", DlnaService.DEFAULT_DLNA_PORT)));
        editPort.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        portRow.addView(editPort, portParams);
        root.addView(portRow);

        // Shared Folders Header
        TextView lblFolders = new TextView(this);
        lblFolders.setText("Shared Folders (Tap folder to remove):");
        lblFolders.setTextColor(Color.DKGRAY);
        lblFolders.setPadding(0, 20, 0, 8);
        root.addView(lblFolders);

        // Folder ListView
        listFolders = new ListView(this);
        folderAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, sharedFolders);
        listFolders.setAdapter(folderAdapter);
        listFolders.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listParams.setMargins(0, 0, 0, 12);
        root.addView(listFolders, listParams);

        listFolders.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                if (isServerRunning) {
                    Toast.makeText(DlnaActivity.this, "Stop server before modifying folders", Toast.LENGTH_SHORT).show();
                    return;
                }
                final String path = sharedFolders.get(position);
                new AlertDialog.Builder(DlnaActivity.this)
                    .setTitle("Remove Folder")
                    .setMessage("Stop sharing " + path + "?")
                    .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sharedFolders.remove(position);
                            folderAdapter.notifyDataSetChanged();
                            saveSettings();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            }
        });

        // Add Folder Button
        btnAddFolder = new Button(this);
        btnAddFolder.setText("+ Add Shared Folder");
        root.addView(btnAddFolder);

        // Start / Stop Button
        btnToggle = new Button(this);
        btnToggle.setText("Start Server");
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(0, 12, 0, 12);
        root.addView(btnToggle, toggleParams);

        // Status Log TextView
        textStatus = new TextView(this);
        textStatus.setText("Status: Stopped\nIP: " + getLocalIpAddress());
        textStatus.setTextColor(Color.DKGRAY);
        textStatus.setTextSize(14);
        root.addView(textStatus);

        setContentView(root);

        // Request Storage Permission safely via Reflection (compatible with API 9 through 33+)
        requestStoragePermissions();

        btnAddFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServerRunning) {
                    Toast.makeText(DlnaActivity.this, "Stop server before adding folders", Toast.LENGTH_SHORT).show();
                    return;
                }
                File startDir = Environment.getExternalStorageDirectory();
                if (startDir == null || !startDir.exists()) startDir = new File("/sdcard");
                if (!startDir.exists()) startDir = new File("/");
                showFolderPicker(startDir);
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(DlnaActivity.this, DlnaService.class);
                if (!isServerRunning) {
                    if (sharedFolders.isEmpty()) {
                        Toast.makeText(DlnaActivity.this, "Please add at least one folder", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port = DlnaService.DEFAULT_DLNA_PORT;
                    try {
                        port = Integer.parseInt(editPort.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        editPort.setText(String.valueOf(port));
                    }
                    saveSettings();

                    serviceIntent.putExtra("PORT", port);
                    serviceIntent.putStringArrayListExtra("FOLDERS", sharedFolders);
                    startService(serviceIntent);

                    btnToggle.setText("Stop Server");
                    editPort.setEnabled(false);
                    btnAddFolder.setEnabled(false);
                    isServerRunning = true;
                    textStatus.setText("Status: RUNNING on http://" + getLocalIpAddress() + ":" + port + "\nSharing " + sharedFolders.size() + " folder(s)");
                    textStatus.setTextColor(Color.rgb(0, 128, 0));
                } else {
                    stopService(serviceIntent);
                    btnToggle.setText("Start Server");
                    editPort.setEnabled(true);
                    btnAddFolder.setEnabled(true);
                    isServerRunning = false;
                    textStatus.setText("Status: Stopped\nIP: " + getLocalIpAddress());
                    textStatus.setTextColor(Color.DKGRAY);
                }
            }
        });
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Method checkPerm = getClass().getMethod("checkSelfPermission", String.class);
                int res = (Integer) checkPerm.invoke(this, "android.permission.READ_EXTERNAL_STORAGE");
                if (res != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerm = getClass().getMethod("requestPermissions", String[].class, int.class);
                    reqPerm.invoke(this, new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.READ_MEDIA_VIDEO",
                        "android.permission.READ_MEDIA_AUDIO",
                        "android.permission.READ_MEDIA_IMAGES"
                    }, 1);
                }
            } catch (Exception ignored) { }
        }
    }

    private void loadSettings() {
        sharedFolders.clear();
        String saved = prefs.getString("FOLDERS", "");
        if (saved.length() > 0) {
            String[] parts = saved.split("\n");
            for (String p : parts) {
                if (p.trim().length() > 0) sharedFolders.add(p.trim());
            }
        }
        if (sharedFolders.isEmpty()) {
            File ext = Environment.getExternalStorageDirectory();
            sharedFolders.add(ext != null ? ext.getAbsolutePath() : "/sdcard");
        }
    }

    private void saveSettings() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sharedFolders.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(sharedFolders.get(i));
        }
        int port = DlnaService.DEFAULT_DLNA_PORT;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (Exception ignored) { }

        prefs.edit()
             .putString("FOLDERS", sb.toString())
             .putInt("PORT", port)
             .commit();
    }

    private void showFolderPicker(final File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "Cannot access folder", Toast.LENGTH_SHORT).show();
            return;
        }

        File[] files = dir.listFiles();
        final List<String> dirs = new ArrayList<String>();
        if (dir.getParentFile() != null) {
            dirs.add(".. (Parent Directory)");
        }
        if (files != null) {
            List<String> subDirs = new ArrayList<String>();
            for (File f : files) {
                if (f.isDirectory() && !f.isHidden()) {
                    subDirs.add(f.getName());
                }
            }
            Collections.sort(subDirs, String.CASE_INSENSITIVE_ORDER);
            dirs.addAll(subDirs);
        } else {
            Toast.makeText(this, "Permission denied reading subfolders", Toast.LENGTH_SHORT).show();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, dirs);
        new AlertDialog.Builder(this)
            .setTitle(dir.getAbsolutePath())
            .setAdapter(adapter, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String item = dirs.get(which);
                    if (item.equals(".. (Parent Directory)")) {
                        if (dir.getParentFile() != null) showFolderPicker(dir.getParentFile());
                    } else {
                        showFolderPicker(new File(dir, item));
                    }
                }
            })
            .setPositiveButton("Select This Folder", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String path = dir.getAbsolutePath();
                    if (!sharedFolders.contains(path)) {
                        sharedFolders.add(path);
                        folderAdapter.notifyDataSetChanged();
                        saveSettings();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wifiManager.getConnectionInfo().getIpAddress();
            if (ip == 0) return "127.0.0.1 (Check Wi-Fi)";
            return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }
}
