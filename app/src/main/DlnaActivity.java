package com.example.dlnaserver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("DLNA Media Server");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 12);
        root.addView(title);

        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView lblPort = new TextView(this);
        lblPort.setText("Port: ");
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

        TextView lblFolders = new TextView(this);
        lblFolders.setText("Shared Folders (Tap to remove):");
        lblFolders.setTextColor(Color.DKGRAY);
        lblFolders.setPadding(0, 16, 0, 6);
        root.addView(lblFolders);

        listFolders = new ListView(this);
        listFolders.setBackgroundColor(Color.rgb(240, 240, 240));
        listFolders.setCacheColorHint(Color.TRANSPARENT); // Fixes Android 2.3 black scroll flicker
        folderAdapter = createBlackTextAdapter(sharedFolders);
        listFolders.setAdapter(folderAdapter);

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listParams.setMargins(0, 0, 0, 10);
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

        btnAddFolder = new Button(this);
        btnAddFolder.setText("+ Add Shared Folder");
        root.addView(btnAddFolder);

        btnToggle = new Button(this);
        btnToggle.setText("Start Server");
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(0, 8, 0, 8);
        root.addView(btnToggle, toggleParams);

        textStatus = new TextView(this);
        textStatus.setText("Status: Stopped\nIP: " + getLocalIpAddress());
        textStatus.setTextColor(Color.DKGRAY);
        textStatus.setTextSize(14);
        root.addView(textStatus);

        setContentView(root);

        requestStoragePermissions();

        btnAddFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServerRunning) {
                    Toast.makeText(DlnaActivity.this, "Stop server before adding folders", Toast.LENGTH_SHORT).show();
                    return;
                }
                File startDir = getDefaultStorageDir();
                showFolderPicker(startDir);
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent serviceIntent = new Intent(DlnaActivity.this, DlnaService.class);
                    if (!isServerRunning) {
                        if (sharedFolders.isEmpty()) {
                            Toast.makeText(DlnaActivity.this, "Add at least one folder", Toast.LENGTH_SHORT).show();
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
                } catch (Throwable t) {
                    Toast.makeText(DlnaActivity.this, "Error: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private ArrayAdapter<String> createBlackTextAdapter(List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = (TextView) view.findViewById(android.R.id.text1);
                if (text != null) {
                    text.setTextColor(Color.BLACK);
                }
                return view;
            }
        };
    }

    private File getDefaultStorageDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) return ext;
        } catch (Throwable ignored) { }
        File sd = new File("/sdcard");
        if (sd.exists()) return sd;
        return new File("/");
    }

    private void requestStoragePermissions() {
        // Android 11+ (API 30+) All Files Access check via reflection
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Method isExternalStorageManager = Environment.class.getMethod("isExternalStorageManager");
                Boolean isManager = (Boolean) isExternalStorageManager.invoke(null);
                if (isManager != null && !isManager.booleanValue()) {
                    Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                }
            } catch (Throwable ignored) { }
        }
        // Android 6.0 - 10 (API 23 - 29) runtime permission check via reflection
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Method checkPerm = Activity.class.getMethod("checkSelfPermission", String.class);
                int res = ((Integer) checkPerm.invoke(this, "android.permission.READ_EXTERNAL_STORAGE")).intValue();
                if (res != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerm = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerm.invoke(this, new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                    }, 1);
                }
            } catch (Throwable ignored) { }
        }
    }

    private void loadSettings() {
        sharedFolders.clear();
        String saved = prefs.getString("FOLDERS", "");
        if (saved != null && saved.length() > 0) {
            String[] parts = saved.split("\n");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.length() > 0) sharedFolders.add(p);
            }
        }
        if (sharedFolders.isEmpty()) {
            sharedFolders.add(getDefaultStorageDir().getAbsolutePath());
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
        } catch (Throwable ignored) { }

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

        File[] files = null;
        try {
            files = dir.listFiles();
        } catch (Throwable ignored) { }

        final List<String> dirs = new ArrayList<String>();
        if (dir.getParentFile() != null) {
            dirs.add(".. (Parent Directory)");
        }
        if (files != null) {
            List<String> subDirs = new ArrayList<String>();
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f.isDirectory() && !f.isHidden()) {
                    subDirs.add(f.getName());
                }
            }
            Collections.sort(subDirs, String.CASE_INSENSITIVE_ORDER);
            dirs.addAll(subDirs);
        } else {
            Toast.makeText(this, "Storage permission needed to list subfolders", Toast.LENGTH_SHORT).show();
            requestStoragePermissions();
        }

        ArrayAdapter<String> adapter = createBlackTextAdapter(dirs);
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

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface intf = en.nextElement();
                Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                while (enumIpAddr.hasMoreElements()) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && (inetAddress instanceof Inet4Address)) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }
}
