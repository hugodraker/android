package com.expense.tracker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.WRITE_EXTERNAL_STORAGE"
            }, 1);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scroll.addView(layout);

        // Control Buttons
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        
        Button btnStart = new Button(this);
        btnStart.setText("START TRACKER");
        btnStart.setOnClickListener(v -> startTracker());
        
        Button btnStop = new Button(this);
        btnStop.setText("STOP TRACKER");
        btnStop.setOnClickListener(v -> stopTracker());
        
        controls.addView(btnStart);
        controls.addView(btnStop);
        layout.addView(controls);

        // Settings Forms
        EditText editIgnore = createInput(layout, "Do Not Record Sites (lat,lon;lat,lon)", prefs.getString("ignore_zones", ""));
        EditText editUser = createInput(layout, "HTTP User", prefs.getString("http_user", "admin"));
        EditText editPass = createInput(layout, "HTTP Pass", prefs.getString("http_pass", "password"));
        EditText editPort = createInput(layout, "HTTP Port", String.valueOf(prefs.getInt("http_port", 8080)));
        EditText editInterval = createInput(layout, "GPS Interval (ms)", String.valueOf(prefs.getLong("interval", 30000)));
        EditText editDist = createInput(layout, "Site Radius (meters)", String.valueOf(prefs.getFloat("distance", 50.0f)));
        EditText editStart = createInput(layout, "Start Hr (0-23)", String.valueOf(prefs.getInt("start_hour", 9)));
        EditText editEnd = createInput(layout, "End Hr (0-23)", String.valueOf(prefs.getInt("end_hour", 17)));

        Button btnSave = new Button(this);
        btnSave.setText("Save Settings");
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("ignore_zones", editIgnore.getText().toString())
                .putString("http_user", editUser.getText().toString())
                .putString("http_pass", editPass.getText().toString())
                .putInt("http_port", Integer.parseInt(editPort.getText().toString()))
                .putLong("interval", Long.parseLong(editInterval.getText().toString()))
                .putFloat("distance", Float.parseFloat(editDist.getText().toString()))
                .putInt("start_hour", Integer.parseInt(editStart.getText().toString()))
                .putInt("end_hour", Integer.parseInt(editEnd.getText().toString()))
                .apply();
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnSave);

        Button btnBackup = new Button(this);
        btnBackup.setText("Export DB to SD Card");
        btnBackup.setOnClickListener(v -> backupDB());
        layout.addView(btnBackup);

        setContentView(scroll);
    }

    private EditText createInput(LinearLayout parent, String hint, String defVal) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(defVal);
        parent.addView(et);
        return et;
    }

    private void startTracker() {
        Intent serviceIntent = new Intent(this, TrackerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);
        Toast.makeText(this, "Tracker Service Started", Toast.LENGTH_SHORT).show();
    }

    private void stopTracker() {
        stopService(new Intent(this, TrackerService.class));
        Toast.makeText(this, "Tracker Service Stopped", Toast.LENGTH_SHORT).show();
    }

    private void backupDB() {
        try {
            File sd = Environment.getExternalStorageDirectory();
            File data = Environment.getDataDirectory();
            if (sd.canWrite()) {
                File currentDB = new File(data, "/data/" + getPackageName() + "/databases/travel_events.db");
                File backupDir = new File(sd, "TrackerBackup");
                backupDir.mkdirs();
                File backupDB = new File(backupDir, "travel_backup.db");

                if (currentDB.exists()) {
                    FileInputStream fis = new FileInputStream(currentDB);
                    FileOutputStream fos = new FileOutputStream(backupDB);
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = fis.read(buffer)) > 0) fos.write(buffer, 0, length);
                    fos.flush();
                    fos.close();
                    fis.close();
                    Toast.makeText(this, "Exported to SD Card", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
