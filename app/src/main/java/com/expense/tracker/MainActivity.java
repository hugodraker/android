package com.expense.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private boolean isTracking = false;
    private Button btnToggle;
    private LinearLayout locationsLayout;
    private EditText editLink;
    private EditText editUser;
    private EditText editPass;
    private EditText editPort;
    private CheckBox chkBoot;
    private CheckBox chkOutputLocations;

    private interface FileAction {
        void execute(String filename);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scroll.addView(layout);

        btnToggle = new Button(this);
        btnToggle.setText("START TRACKER");
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTracker(!isTracking);
            }
        });
        layout.addView(btnToggle);

        Button btnAdmin = new Button(this);
        btnAdmin.setText("Enable System Admin & Never-Sleep Mode");
        btnAdmin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableAdminAndBatteryExemption();
            }
        });
        layout.addView(btnAdmin);

        chkBoot = new CheckBox(this);
        chkBoot.setText("Start Tracker When Phone Starts");
        chkBoot.setChecked(prefs.getBoolean("start_on_boot", true));
        layout.addView(chkBoot);

        chkOutputLocations = new CheckBox(this);
        chkOutputLocations.setText("Output locations");
        chkOutputLocations.setChecked(prefs.getBoolean("output_locations", false));
        chkOutputLocations.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("output_locations", isChecked);
                ed.commit();
            }
        });
        layout.addView(chkOutputLocations);

        Button btnAddKnown = new Button(this);
        btnAddKnown.setText("Add to Known Named Locations (Current GPS)");
        btnAddKnown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptAddCurrentLocationToNamed();
            }
        });
        layout.addView(btnAddKnown);

        addLabel(layout, "Copy/Paste Server Link (includes &view=1):");
        editLink = new EditText(this);
        editLink.setSelectAllOnFocus(true);
        layout.addView(editLink);

        editUser = createLabeledInput(layout, "HTTP Username:", prefs.getString("http_user", "admin"));
        editPass = createLabeledInput(layout, "HTTP Password:", prefs.getString("http_pass", "password"));
        editPort = createLabeledInput(layout, "HTTP Port:", String.valueOf(prefs.getInt("http_port", 8080)));
        final EditText editInterval = createLabeledInput(layout, "GPS Check Interval (ms) [60000 = 1 min]:", String.valueOf(prefs.getLong("interval", 60000L)));
        final EditText editStayMins = createLabeledInput(layout, "Min Stay Time to Confirm Site (minutes):", String.valueOf(prefs.getInt("stay_mins", 5)));
        final EditText editDist = createLabeledInput(layout, "Site Radius Tolerance (meters):", String.valueOf(prefs.getFloat("distance", 75.0f)));
        final EditText editStartHr = createLabeledInput(layout, "Work Start Hour (0-23):", String.valueOf(prefs.getInt("start_hour", 0)));
        final EditText editEndHr = createLabeledInput(layout, "Work End Hour (1-24):", String.valueOf(prefs.getInt("end_hour", 24)));

        // Live-update link whenever password, username, or port changes
        TextWatcher linkWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putString("http_user", editUser.getText().toString().trim());
                ed.putString("http_pass", editPass.getText().toString().trim());
                ed.putInt("http_port", parseIntSafe(editPort.getText().toString(), 8080));
                ed.commit();
                updateLinkText(false);
            }
        };
        editUser.addTextChangedListener(linkWatcher);
        editPass.addTextChangedListener(linkWatcher);
        editPort.addTextChangedListener(linkWatcher);

        addLabel(layout, "\nSpecial Locations (Tap OMIT/TRACK to toggle, Long-Press OMIT/TRACK to Delete):");
        locationsLayout = new LinearLayout(this);
        locationsLayout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(locationsLayout);

        Button btnAddLoc = new Button(this);
        btnAddLoc.setText("+ Add Blank Special Location Row");
        btnAddLoc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLocationRow("OFFICE", "", false);
            }
        });
        layout.addView(btnAddLoc);

        loadLocationsFromPrefs();

        Button btnSave = new Button(this);
        btnSave.setText("Save Settings");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor ed = prefs.edit();
                ed.putBoolean("start_on_boot", chkBoot.isChecked());
                ed.putBoolean("output_locations", chkOutputLocations.isChecked());
                ed.putString("http_user", editUser.getText().toString().trim());
                ed.putString("http_pass", editPass.getText().toString().trim());
                ed.putInt("http_port", parseIntSafe(editPort.getText().toString(), 8080));
                ed.putLong("interval", parseLongSafe(editInterval.getText().toString(), 60000L));
                ed.putInt("stay_mins", parseIntSafe(editStayMins.getText().toString(), 5));
                ed.putFloat("distance", parseFloatSafe(editDist.getText().toString(), 75.0f));
                ed.putInt("start_hour", parseIntSafe(editStartHr.getText().toString(), 0));
                ed.putInt("end_hour", parseIntSafe(editEndHr.getText().toString(), 24));
                ed.commit();

                saveLocationsToPrefs();
                updateLinkText(true);
                Toast.makeText(MainActivity.this, "Settings Saved. Restarting Tracker...", Toast.LENGTH_SHORT).show();
                if (isTracking) {
                    toggleTracker(false);
                    toggleTracker(true);
                }
            }
        });
        layout.addView(btnSave);

        LinearLayout ioLayout = new LinearLayout(this);
        ioLayout.setOrientation(LinearLayout.HORIZONTAL);

        Button btnExportDb = new Button(this);
        btnExportDb.setText("Export DB");
        btnExportDb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptFilename("travel_data.db", "Export DB Filename", new FileAction() {
                    @Override
                    public void execute(String filename) {
                        exportDb(filename);
                    }
                });
            }
        });

        Button btnImportDb = new Button(this);
        btnImportDb.setText("Import DB");
        btnImportDb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptFilename("travel_data.db", "Import DB Filename", new FileAction() {
                    @Override
                    public void execute(String filename) {
                        importDb(filename);
                    }
                });
            }
        });

        Button btnExportCfg = new Button(this);
        btnExportCfg.setText("Export Cfg");
        btnExportCfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptFilename("tracker_settings.cfg", "Export Settings Filename", new FileAction() {
                    @Override
                    public void execute(String filename) {
                        exportSettings(filename);
                    }
                });
            }
        });

        Button btnImportCfg = new Button(this);
        btnImportCfg.setText("Import Cfg");
        btnImportCfg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptFilename("tracker_settings.cfg", "Import Settings Filename", new FileAction() {
                    @Override
                    public void execute(String filename) {
                        importSettings(filename);
                    }
                });
            }
        });

        ioLayout.addView(btnExportDb);
        ioLayout.addView(btnImportDb);
        ioLayout.addView(btnExportCfg);
        ioLayout.addView(btnImportCfg);
        layout.addView(ioLayout);

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
            }, 1);
        } else {
            toggleTracker(true);
        }

        updateLinkText(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh local IP and select the link text every time the app is opened/resumed
        updateLinkText(true);
    }

    private void enableAdminAndBatteryExemption() {
        try {
            ComponentName adminComponent = new ComponentName(this, TrackerDeviceAdminReceiver.class);
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && !dpm.isAdminActive(adminComponent)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keeps Travel Expense Tracker active in the background without sleeping.");
                startActivity(intent);
            } else {
                Toast.makeText(this, "Device Admin is already active.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= 23) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleTracker(true);
        } else {
            Toast.makeText(this, "Location permission is required to track.", Toast.LENGTH_LONG).show();
        }
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

    private void promptAddCurrentLocationToNamed() {
        double lat = 0.0;
        double lon = 0.0;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc != null && (Math.abs(loc.getLatitude()) > 0.0001 || Math.abs(loc.getLongitude()) > 0.0001)) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
            }
        } catch (Exception ignored) {}

        if (Math.abs(lat) <= 0.0001 && Math.abs(lon) <= 0.0001) {
            lat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
            lon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));
        }

        if (Math.abs(lat) <= 0.0001 && Math.abs(lon) <= 0.0001) {
            Toast.makeText(this, "No valid GPS fix available yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String coordStr = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Known Named Location (" + coordStr + ")");
        final EditText input = new EditText(this);
        input.setText("HOME");
        builder.setView(input);
        builder.setPositiveButton("Add", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = input.getText().toString().trim();
                if (name.length() == 0) name = "HOME";
                addLocationRow(name.toUpperCase(Locale.US), coordStr, true);
                saveLocationsToPrefs();
                Toast.makeText(MainActivity.this, "Added " + name + " (" + coordStr + ")", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface intf = interfaces.nextElement();
                    Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                    while (enumIpAddr.hasMoreElements()) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                            String ip = inetAddress.getHostAddress();
                            if (ip != null && ip.length() > 0 && !ip.equals("0.0.0.0")) {
                                return ip;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void updateLinkText(boolean selectText) {
        if (editLink == null) return;
        String ip = getLocalIpAddress();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String today = sdf.format(new Date());

        String user = (editUser != null) ? editUser.getText().toString().trim() : prefs.getString("http_user", "admin");
        String pass = (editPass != null) ? editPass.getText().toString().trim() : prefs.getString("http_pass", "password");
        int port = (editPort != null) ? parseIntSafe(editPort.getText().toString(), 8080) : prefs.getInt("http_port", 8080);

        String url = "http://" + ip + ":" + port +
                     "/download?" + user + "=" + pass +
                     "&" + today + "0000&" + today + "1700&view=1";
        editLink.setText(url);

        if (selectText) {
            editLink.post(new Runnable() {
                @Override
                public void run() {
                    editLink.requestFocus();
                    editLink.selectAll();
                }
            });
        }
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        parent.addView(tv);
    }

    private EditText createLabeledInput(LinearLayout parent, String label, String defVal) {
        addLabel(parent, label);
        EditText et = new EditText(this);
        et.setText(defVal);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }

    private void addLocationRow(String name, String coords, boolean omitted) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final EditText eName = new EditText(this);
        eName.setText(name);
        eName.setHint("Name");
        eName.setWidth(180);

        EditText eCoords = new EditText(this);
        eCoords.setText(coords);
        eCoords.setHint("Lat,Lon");
        eCoords.setWidth(280);

        final Button btnType = new Button(this);
        btnType.setText(omitted ? "OMIT" : "TRACK");
        btnType.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnType.setText(btnType.getText().toString().equals("OMIT") ? "TRACK" : "OMIT");
                saveLocationsToPrefs();
            }
        });

        btnType.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                String locTitle = eName.getText().toString().trim();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete Location")
                        .setMessage("Delete '" + (locTitle.length() > 0 ? locTitle : "this entry") + "'?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                locationsLayout.removeView(row);
                                saveLocationsToPrefs();
                                Toast.makeText(MainActivity.this, "Entry deleted", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });

        row.addView(eName);
        row.addView(eCoords);
        row.addView(btnType);
        locationsLayout.addView(row);
    }

    private void saveLocationsToPrefs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < locationsLayout.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) locationsLayout.getChildAt(i);
            String name = ((EditText) row.getChildAt(0)).getText().toString().replace(";", "").replace("|", "").trim();
            String coords = ((EditText) row.getChildAt(1)).getText().toString().replace(";", "").replace("|", "").trim();
            boolean omit = ((Button) row.getChildAt(2)).getText().toString().equals("OMIT");

            if (name.length() > 0 && coords.length() > 0) {
                sb.append(name).append("|").append(coords).append("|").append(omit).append(";");
            }
        }
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString("special_locations", sb.toString());
        ed.commit();
    }

    private void loadLocationsFromPrefs() {
        locationsLayout.removeAllViews();
        String saved = prefs.getString("special_locations", "");
        if (saved.length() == 0) {
            addLocationRow("OFFICE", "", false);
            return;
        }
        String[] entries = saved.split(";");
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|");
            if (parts.length == 3) {
                addLocationRow(parts[0], parts[1], Boolean.parseBoolean(parts[2]));
            }
        }
    }

    private void promptFilename(String defaultName, String title, final FileAction action) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        final EditText input = new EditText(this);
        input.setText(defaultName);
        builder.setView(input);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                action.execute(input.getText().toString().trim());
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void exportDb(String filename) {
        try {
            File currentDB = getDatabasePath("travel_events.db");
            File backupDir = new File(Environment.getExternalStorageDirectory(), "TrackerBackup");
            backupDir.mkdirs();
            copyFile(currentDB, new File(backupDir, filename));
            Toast.makeText(this, "Exported DB: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "DB Export failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void importDb(String filename) {
        try {
            File backupFile = new File(Environment.getExternalStorageDirectory(), "TrackerBackup/" + filename);
            File currentDB = getDatabasePath("travel_events.db");
            copyFile(backupFile, currentDB);
            Toast.makeText(this, "Imported DB. Restarting...", Toast.LENGTH_LONG).show();
            if (isTracking) {
                toggleTracker(false);
                toggleTracker(true);
            }
        } catch (Exception e) {
            Toast.makeText(this, "DB Import failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportSettings(String filename) {
        try {
            File prefsFile = new File(getApplicationInfo().dataDir + "/shared_prefs/TrackerPrefs.xml");
            File backupDir = new File(Environment.getExternalStorageDirectory(), "TrackerBackup");
            backupDir.mkdirs();
            copyFile(prefsFile, new File(backupDir, filename));
            Toast.makeText(this, "Exported Settings: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Settings Export failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void importSettings(String filename) {
        try {
            File backupFile = new File(Environment.getExternalStorageDirectory(), "TrackerBackup/" + filename);
            File prefsFile = new File(getApplicationInfo().dataDir + "/shared_prefs/TrackerPrefs.xml");
            copyFile(backupFile, prefsFile);
            Toast.makeText(this, "Imported Settings. Reopen app to view.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Settings Import failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = fis.read(buffer)) > 0) fos.write(buffer, 0, len);
        fos.close();
        fis.close();
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }
            }
