package com.expense.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private boolean isTracking = false;
    private Button btnToggle;
    private LinearLayout locationsLayout;
    private EditText editLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
            }, 1);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scroll.addView(layout);

        // Auto-start tracker on open
        toggleTracker(true);

        // Toggle Button
        btnToggle = new Button(this);
        btnToggle.setText("STOP TRACKER");
        btnToggle.setOnClickListener(v -> toggleTracker(!isTracking));
        layout.addView(btnToggle);

        // Don't Track (Omit Current Location)
        Button btnOmit = new Button(this);
        btnOmit.setText("Don't Track Here (Add to Omitted)");
        btnOmit.setOnClickListener(v -> addCurrentLocationToOmitted());
        layout.addView(btnOmit);

        // HTTP Link
        TextView lblLink = new TextView(this);
        lblLink.setText("Copy/Paste Server Download Link:");
        layout.addView(lblLink);
        
        editLink = new EditText(this);
        editLink.setTextIsSelectable(true);
        layout.addView(editLink);
        updateLinkText();

        // Settings Forms
        EditText editUser = createInput(layout, "HTTP Username (Admin)", prefs.getString("http_user", "admin"));
        EditText editPass = createInput(layout, "HTTP Password", prefs.getString("http_pass", "password"));
        EditText editPort = createInput(layout, "HTTP Port", String.valueOf(prefs.getInt("http_port", 8080)));
        EditText editInterval = createInput(layout, "GPS Wake Interval (ms) [30000=30s]", String.valueOf(prefs.getLong("interval", 30000)));
        EditText editDist = createInput(layout, "Site Radius Tolerance (meters)", String.valueOf(prefs.getFloat("distance", 50.0f)));

        // Special Locations
        TextView lblLocs = new TextView(this);
        lblLocs.setText("\nSpecial Locations (Names & Coords):");
        layout.addView(lblLocs);

        locationsLayout = new LinearLayout(this);
        locationsLayout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(locationsLayout);

        Button btnAddLoc = new Button(this);
        btnAddLoc.setText("+ Add Special Location");
        btnAddLoc.setOnClickListener(v -> addLocationRow("OFFICE", "", false));
        layout.addView(btnAddLoc);

        loadLocationsFromPrefs();

        Button btnSave = new Button(this);
        btnSave.setText("Save Settings");
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("http_user", editUser.getText().toString())
                .putString("http_pass", editPass.getText().toString())
                .putInt("http_port", Integer.parseInt(editPort.getText().toString()))
                .putLong("interval", Long.parseLong(editInterval.getText().toString()))
                .putFloat("distance", Float.parseFloat(editDist.getText().toString()))
                .apply();
            saveLocationsToPrefs();
            updateLinkText();
            Toast.makeText(this, "Settings Saved. Restarting Tracker...", Toast.LENGTH_SHORT).show();
            toggleTracker(false);
            toggleTracker(true);
        });
        layout.addView(btnSave);

        // Import/Export
        LinearLayout ioLayout = new LinearLayout(this);
        ioLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        Button btnExport = new Button(this);
        btnExport.setText("Export DB");
        btnExport.setOnClickListener(v -> promptFilename("travel_data.db", "Export DB Name", this::exportFile));
        
        Button btnImport = new Button(this);
        btnImport.setText("Import DB");
        btnImport.setOnClickListener(v -> promptFilename("travel_data.db", "Import DB Name", this::importFile));

        ioLayout.addView(btnExport);
        ioLayout.addView(btnImport);
        layout.addView(ioLayout);

        setContentView(scroll);
    }

    private void toggleTracker(boolean start) {
        Intent serviceIntent = new Intent(this, TrackerService.class);
        if (start) {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
            else startService(serviceIntent);
            isTracking = true;
            if (btnToggle != null) btnToggle.setText("STOP TRACKER");
        } else {
            stopService(serviceIntent);
            isTracking = false;
            if (btnToggle != null) btnToggle.setText("START TRACKER");
        }
    }

    private void addCurrentLocationToOmitted() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            
            if (loc != null) {
                addLocationRow("HOME", loc.getLatitude() + "," + loc.getLongitude(), true);
                Toast.makeText(this, "Added Current Location as Omitted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No GPS fix available", Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateLinkText() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        if (ip.equals("0.0.0.0")) ip = "127.0.0.1";

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String today = sdf.format(new Date());
        
        String url = "http://" + ip + ":" + prefs.getInt("http_port", 8080) + 
                     "/download?" + prefs.getString("http_user", "admin") + "=" + prefs.getString("http_pass", "password") +
                     "&" + today + "0000&" + today + "1700";
        editLink.setText(url);
    }

    private EditText createInput(LinearLayout parent, String hint, String defVal) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(defVal);
        parent.addView(et);
        return et;
    }

    private void addLocationRow(String name, String coords, boolean omitted) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        
        EditText eName = new EditText(this);
        eName.setText(name);
        eName.setHint("Name");
        
        EditText eCoords = new EditText(this);
        eCoords.setText(coords);
        eCoords.setHint("Lat,Lon");
        
        Button btnType = new Button(this);
        btnType.setText(omitted ? "OMIT" : "TRACK");
        btnType.setOnClickListener(v -> btnType.setText(btnType.getText().toString().equals("OMIT") ? "TRACK" : "OMIT"));

        row.addView(eName);
        row.addView(eCoords);
        row.addView(btnType);
        locationsLayout.addView(row);
    }

    private void saveLocationsToPrefs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < locationsLayout.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) locationsLayout.getChildAt(i);
            String name = ((EditText) row.getChildAt(0)).getText().toString().replace(";", "").replace("|", "");
            String coords = ((EditText) row.getChildAt(1)).getText().toString().replace(";", "").replace("|", "");
            boolean omit = ((Button) row.getChildAt(2)).getText().toString().equals("OMIT");
            
            if (!name.isEmpty() && !coords.isEmpty()) {
                sb.append(name).append("|").append(coords).append("|").append(omit).append(";");
            }
        }
        prefs.edit().putString("special_locations", sb.toString()).apply();
    }

    private void loadLocationsFromPrefs() {
        locationsLayout.removeAllViews();
        String saved = prefs.getString("special_locations", "");
        if (saved.isEmpty()) return;
        
        for (String entry : saved.split(";")) {
            String[] parts = entry.split("\\|");
            if (parts.length == 3) {
                addLocationRow(parts[0], parts[1], Boolean.parseBoolean(parts[2]));
            }
        }
    }

    private void promptFilename(String defaultName, String title, java.util.function.Consumer<String> action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        final EditText input = new EditText(this);
        input.setText(defaultName);
        builder.setView(input);
        builder.setPositiveButton("OK", (dialog, which) -> action.accept(input.getText().toString()));
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void exportFile(String filename) {
        try {
            File sd = Environment.getExternalStorageDirectory();
            File currentDB = new File(Environment.getDataDirectory(), "/data/" + getPackageName() + "/databases/travel_events.db");
            File backupDir = new File(sd, "TrackerBackup");
            backupDir.mkdirs();
            copyFile(currentDB, new File(backupDir, filename));
            Toast.makeText(this, "Exported to SD Card: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show(); }
    }

    private void importFile(String filename) {
        try {
            File sd = Environment.getExternalStorageDirectory();
            File backupFile = new File(sd, "TrackerBackup/" + filename);
            File currentDB = new File(Environment.getDataDirectory(), "/data/" + getPackageName() + "/databases/travel_events.db");
            copyFile(backupFile, currentDB);
            Toast.makeText(this, "Imported from SD Card. Restarting Tracker...", Toast.LENGTH_LONG).show();
            toggleTracker(false);
            toggleTracker(true);
        } catch (Exception e) { Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show(); }
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buffer = new byte[1024];
        int len;
        while ((len = fis.read(buffer)) > 0) fos.write(buffer, 0, len);
        fos.close();
        fis.close();
    }
}
