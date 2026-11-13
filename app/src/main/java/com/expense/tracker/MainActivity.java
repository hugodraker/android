package com.expense.tracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import/**/;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private boolean isTracking = false;
    private Button btnToggle;
    private LinearLayout locationsLayout;
    private EditText editLink;
    private EditText editCurrentCoords;
    private EditText editUser;
    private EditText editPass;
    private EditText editPort;
    private EditText editInterval;
    private EditText editStayMins;
    private EditText editDist;
    private EditText editStartHr;
    private EditText editEndHr;
    private CheckBox chkBoot;
    private CheckBox chkOutputLocations;

    private Handler uiIntervalHandler;
    private boolean receiverRegistered = false;

    private interface FileAction {
        void execute(String filename);
    }

    private final BroadcastReceiver coordReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && TrackerService.ACTION_LOCATION_UPDATED.equals(intent.getAction())) {
                refreshCurrentCoordinatesBox();
            }
        }
    };

    private final Runnable uiCoordUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            refreshCurrentCoordinatesBox();
            long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
            uiIntervalHandler.postDelayed(this, interval);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        uiIntervalHandler = new Handler(Looper.getMainLooper());

        if (!prefs.getBoolean("hours_migrated_24", false)) {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putInt("start_hour", 0);
            ed.putInt("end_hour", 24);
            ed.putBoolean("hours_migrated_24", true);
            ed.commit();
        }

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

        addLabel(layout, "Current Coordinates (Updates at Set Interval):");
        editCurrentCoords = new EditText(this);
        editCurrentCoords.setSingleLine(true);
        editCurrentCoords.setSelectAllOnFocus(true);
        layout.addView(editCurrentCoords);

        addLabel(layout, "Copy/Paste Local Server Link (includes &view=1):");
        editLink = new EditText(this);
        editLink.setSelectAllOnFocus(true);
        layout.addView(editLink);

        editUser = createLabeledInput(layout, "HTTP Username:", prefs.getString("http_user", "admin"));
        editPass = createLabeledInput(layout, "HTTP Password:", prefs.getString("http_pass", "password"));
        editPort = createLabeledInput(layout, "HTTP Port:", String.valueOf(prefs.getInt("http_port", 8080)));
        editInterval = createLabeledInput(layout, "GPS Check Interval (ms) [60000 = 1 min]:", String.valueOf(prefs.getLong("interval", 60000L)));
        editStayMins = createLabeledInput(layout, "Min Stay Time to Confirm Site (minutes):", String.valueOf(prefs.getInt("stay_mins", 5)));
        editDist = createLabeledInput(layout, "Site Radius Tolerance (meters, e.g. .5 or 75):", String.valueOf(prefs.getFloat("distance", 75.0f)));
        editStartHr = createLabeledInput(layout, "Work Start Hour (0-23):", String.valueOf(prefs.getInt("start_hour", 0)));
        editEndHr = createLabeledInput(layout, "Work End Hour (1-24):", String.valueOf(prefs.getInt("end_hour", 24)));

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
                saveAllSettingsToPrefs();
                Toast.makeText(MainActivity.this, "Settings Saved.", Toast.LENGTH_SHORT).show();
                if (isTracking) {
                    reloadServiceSettings();
                }
                restartUiCoordTimer();
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

        Button btnEraseAll = new Button(this);
        btnEraseAll.setText("Erase All Saved Data");
        btnEraseAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Erase All Saved Data")
                        .setMessage("Are you sure you want to permanently erase all recorded travel events and reset current trip memory?")
                        .setPositiveButton("Erase Everything", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                eraseAllSavedData();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        layout.addView(btnEraseAll);

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
            }, 1);
        } else {
            toggleTracker(true);
        }

        refreshCurrentCoordinatesBox();
        updateLinkText(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(TrackerService.ACTION_LOCATION_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(coordReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(coordReceiver, filter);
            }
            receiverRegistered = true;
        }
        refreshCurrentCoordinatesBox();
        restartUiCoordTimer();
        updateLinkText(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(coordReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        uiIntervalHandler.removeCallbacks(uiCoordUpdateRunnable);
    }

    private void restartUiCoordTimer() {
        uiIntervalHandler.removeCallbacks(uiCoordUpdateRunnable);
        long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
        uiIntervalHandler.postDelayed(uiCoordUpdateRunnable, interval);
    }

    private void refreshCurrentCoordinatesBox() {
        if (editCurrentCoords == null) return;
        double lat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));

        if (Math.abs(lat) <= 0.0001 && Math.abs(lon) <= 0.0001) {
            Location loc = getBestNativeLocation();
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
                SharedPreferences.Editor ed = prefs.edit();
                ed.putLong("last_valid_lat", Double.doubleToRawLongBits(lat));
                ed.putLong("last_valid_lon", Double.doubleToRawLongBits(lon));
                ed.commit();
            }
        }

        if (Math.abs(lat) > 0.0001 || Math.abs(lon) > 0.0001) {
            editCurrentCoords.setText(String.format(Locale.US, "%.6f,%.6f", lat, lon));
        } else {
            editCurrentCoords.setText("Acquiring satellite lock...");
        }
    }

    // Queries all native OS providers (like Organic Maps) without Google Play Services
    private Location getBestNativeLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            List<String> providers = lm.getAllProviders();
            Location best = null;
            if (providers != null) {
                for (int i = 0; i < providers.size(); i++) {
                    String p = providers.get(i);
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (Math.abs(l.getLatitude()) > 0.0001 || Math.abs(l.getLongitude()) > 0.0001)) {
                        if (best == null || l.getTime() > best.getTime()) {
                            best = l;
                        }
                    }
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveAllSettingsToPrefs() {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("start_on_boot", chkBoot.isChecked());
        ed.putBoolean("output_locations", chkOutputLocations.isChecked());
        ed.putString("http_user", editUser.getText().toString().trim());
        ed.putString("http_pass", editPass.getText().toString().trim());
        ed.putInt("http_port", parseIntSafe(editPort.getText().toString(), 8080));
        ed.putLong("interval", Math.max(1000L, parseLongSafe(editInterval.getText().toString(), 60000L)));
        ed.putInt("stay_mins", parseIntSafe(editStayMins.getText().toString(), 5));
        ed.putFloat("distance", Math.max(0.05f, parseFloatSafe(editDist.getText().toString(), 75.0f)));
        ed.putInt("start_hour", parseIntSafe(editStartHr.getText().toString(), 0));
        ed.putInt("end_hour", parseIntSafe(editEndHr.getText().toString(), 24));
        ed.commit();
        saveLocationsToPrefs();
        updateLinkText(true);
    }

    private void eraseAllSavedData() {
        try {
            File dbFile = getDatabasePath("travel_events.db");
            if (dbFile.exists()) {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                db.execSQL("DELETE FROM events");
                db.close();
            }
        } catch (Exception ignored) {}

        SharedPreferences.Editor ed = prefs.edit();
        ed.remove("anchor_lat");
        ed.remove("anchor_lon");
        ed.remove("anchor_start");
        ed.remove("anchor_last_seen");
        ed.commit();

        Intent serviceIntent = new Intent(this, TrackerService.class);
        serviceIntent.setAction(TrackerService.ACTION_ERASE_DATA);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);

        Toast.makeText(this, "All saved data erased.", Toast.LENGTH_LONG).show();
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
            refreshCurrentCoordinatesBox();
        } else {
            Toast.makeText(this, "Location permission is required to track.", Toast.LENGTH_LONG).show();
        }
    }

    private void reloadServiceSettings() {
        Intent serviceIntent = new Intent(this, TrackerService.class);
        serviceIntent.setAction(TrackerService.ACTION_RELOAD_SETTINGS);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);
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
        double lat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
        double lon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));

        if (Math.abs(lat) <= 0.0001 && Math.abs(lon) <= 0.0001) {
            Location loc = getBestNativeLocation();
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
            }
        }

        if (Math.abs(lat) <= 0.0001 && Math.abs(lon) <= 0.0001 && editCurrentCoords != null) {
            String boxText = editCurrentCoords.getText().toString().trim();
            if (boxText.contains(",")) {
                try {
                    String[] p = boxText.split(",");
                    lat = Double.parseDouble(p[0].trim());
                    lon = Double.parseDouble(p[1].trim());
                } catch (Exception ignored) {}
            }
        }

        if (Math.abs(lat) <= 0.0001 && Math.abs(lon) <= 0.0001) {
            Toast.makeText(this, "Still acquiring satellite fix. Step outdoors or wait a moment.", Toast.LENGTH_LONG).show();
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
                if (isTracking) reloadServiceSettings();
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
                if (isTracking) reloadServiceSettings();
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
                                if (isTracking) reloadServiceSettings();
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

    private List<File> getCandidateBackupDirs() {
        List<File> dirs = new ArrayList<File>();
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                dirs.add(new File(ext, "TrackerBackup"));
                dirs.add(new File(ext, "Download"));
            }
        } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 8) {
                File appExt = getExternalFilesDir(null);
                if (appExt != null) dirs.add(appExt);
            }
        } catch (Exception ignored) {}
        dirs.add(getFilesDir());
        return dirs;
    }

    private void exportDb(String filename) {
        File currentDB = getDatabasePath("travel_events.db");
        if (!currentDB.exists()) {
            try {
                if (currentDB.getParentFile() != null) currentDB.getParentFile().mkdirs();
                SQLiteDatabase tmp = SQLiteDatabase.openOrCreateDatabase(currentDB, null);
                tmp.execSQL("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, lat REAL, lon REAL, start_time INTEGER, end_time INTEGER, duration_hours REAL, dist_km REAL, dist_mi REAL)");
                tmp.close();
            } catch (Exception ignored) {}
        }

        for (File dir : getCandidateBackupDirs()) {
            try {
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, filename);
                copyFile(currentDB, out);
                if (out.exists() && out.length() > 0) {
                    Toast.makeText(this, "Exported DB to:\n" + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {}
        }
        Toast.makeText(this, "DB Export failed on all storage paths", Toast.LENGTH_LONG).show();
    }

    private void importDb(String filename) {
        File sourceFile = null;
        File direct = new File(filename);
        if (direct.isAbsolute() && direct.exists()) {
            sourceFile = direct;
        } else {
            for (File dir : getCandidateBackupDirs()) {
                File candidate = new File(dir, filename);
                if (candidate.exists() && candidate.length() > 0) {
                    sourceFile = candidate;
                    break;
                }
            }
        }

        if (sourceFile == null) {
            Toast.makeText(this, "Backup file not found: " + filename, Toast.LENGTH_LONG).show();
            return;
        }

        boolean wasTracking = isTracking;
        if (wasTracking) {
            toggleTracker(false);
        }

        try {
            File currentDB = getDatabasePath("travel_events.db");
            if (currentDB.getParentFile() != null) currentDB.getParentFile().mkdirs();
            new File(currentDB.getAbsolutePath() + "-wal").delete();
            new File(currentDB.getAbsolutePath() + "-shm").delete();
            new File(currentDB.getAbsolutePath() + "-journal").delete();

            copyFile(sourceFile, currentDB);
            Toast.makeText(this, "Imported DB from:\n" + sourceFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "DB Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        if (wasTracking) {
            toggleTracker(true);
        }
    }

    private void exportSettings(String filename) {
        saveAllSettingsToPrefs();
        Map<String, ?> all = prefs.getAll();

        for (File dir : getCandidateBackupDirs()) {
            FileWriter fw = null;
            try {
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, filename);
                fw = new FileWriter(out, false);
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String k = entry.getKey();
                    Object v = entry.getValue();
                    if (v instanceof Boolean) fw.write(k + "=B:" + v + "\n");
                    else if (v instanceof Integer) fw.write(k + "=I:" + v + "\n");
                    else if (v instanceof Long) fw.write(k + "=L:" + v + "\n");
                    else if (v instanceof Float) fw.write(k + "=F:" + v + "\n");
                    else if (v instanceof String) fw.write(k + "=S:" + v.toString().replace("\n", "") + "\n");
                }
                fw.flush();
                fw.close();
                fw = null;
                if (out.exists()) {
                    Toast.makeText(this, "Exported Settings to:\n" + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {
            } finally {
                if (fw != null) try { fw.close(); } catch (Exception ignored) {}
            }
        }
        Toast.makeText(this, "Settings Export failed", Toast.LENGTH_SHORT).show();
    }

    private void importSettings(String filename) {
        File sourceFile = null;
        File direct = new File(filename);
        if (direct.isAbsolute() && direct.exists()) {
            sourceFile = direct;
        } else {
            for (File dir : getCandidateBackupDirs()) {
                File candidate = new File(dir, filename);
                if (candidate.exists()) {
                    sourceFile = candidate;
                    break;
                }
            }
        }

        if (sourceFile == null) {
            Toast.makeText(this, "Settings file not found: " + filename, Toast.LENGTH_LONG).show();
            return;
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(sourceFile));
            SharedPreferences.Editor ed = prefs.edit();
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0 || eq + 3 > line.length()) continue;
                String key = line.substring(0, eq).trim();
                String type = line.substring(eq + 1, eq + 3);
                String val = line.substring(eq + 3);

                if ("B:".equals(type)) ed.putBoolean(key, Boolean.parseBoolean(val));
                else if ("I:".equals(type)) ed.putInt(key, parseIntSafe(val, 0));
                else if ("L:".equals(type)) ed.putLong(key, parseLongSafe(val, 0L));
                else if ("F:".equals(type)) ed.putFloat(key, parseFloatSafe(val, 0.0f));
                else if ("S:".equals(type)) ed.putString(key, val);
            }
            ed.commit();

            chkBoot.setChecked(prefs.getBoolean("start_on_boot", true));
            chkOutputLocations.setChecked(prefs.getBoolean("output_locations", false));
            editUser.setText(prefs.getString("http_user", "admin"));
            editPass.setText(prefs.getString("http_pass", "password"));
            editPort.setText(String.valueOf(prefs.getInt("http_port", 8080)));
            editInterval.setText(String.valueOf(prefs.getLong("interval", 60000L)));
            editStayMins.setText(String.valueOf(prefs.getInt("stay_mins", 5)));
            editDist.setText(String.valueOf(prefs.getFloat("distance", 75.0f)));
            editStartHr.setText(String.valueOf(prefs.getInt("start_hour", 0)));
            editEndHr.setText(String.valueOf(prefs.getInt("end_hour", 24)));
            loadLocationsFromPrefs();
            updateLinkText(true);
            refreshCurrentCoordinatesBox();

            if (isTracking) reloadServiceSettings();
            Toast.makeText(this, "Imported Settings from:\n" + sourceFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Settings Import failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = fis.read(buffer)) > 0) fos.write(buffer, 0, len);
        fos.getFD().sync();
        fos.close();
        fis.close();
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    // Supports leading decimal values like ".5" or ",5" -> 0.5f
    private float parseFloatSafe(String s, float def) {
        if (s == null) return def;
        String clean = s.trim().replace(',', '.');
        if (clean.startsWith(".")) clean = "0" + clean;
        else if (clean.startsWith("-.")) clean = "-0" + clean.substring(1);
        try {
            return Float.parseFloat(clean);
        } catch (Exception e) {
            return def;
        }
    }
}
