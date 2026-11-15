package com.HUGO.notifsounder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.util.List;

public class MainActivity extends Activity {

    private ConfigStore store;

    // Top Controls
    private Button btnAckStop;
    private CheckBox cbMonitorEnabled, cbRunOnStart;

    // Sound Profile Editor Controls
    private Spinner spProfiles;
    private ArrayAdapter<String> profileSpinnerAdapter;
    private final List<String> profileNames = new ArrayList<String>();
    private EditText etProfName, etProfSoundPath, etProfVolume;
    private CheckBox cbProfIgnoreDnd, cbProfVibrate;
    private boolean updatingProfileUI = false;

    // Unlimited Events Controls
    private Spinner spEvents;
    private ArrayAdapter<String> eventSpinnerAdapter;
    private final List<String> eventLabels = new ArrayList<String>();
    private EditText etEvName, etEvAppFilter, etEvMatchText, etEvLuaScript;
    private Spinner spEvProfile;
    private CheckBox cbEvEnabled, cbEvRepeatAck;
    private TextView tvStatus;
    private boolean updatingEventUI = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            requestWindowFeature(Window.FEATURE_LEFT_ICON);
        } catch (Throwable ignored) {}

        ensureModernPermissions();

        store = new ConfigStore();
        store.load(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);

        // 1. ACKNOWLEDGE / STOP REPEATING ALARM BUTTON
        btnAckStop = new Button(this);
        btnAckStop.setText("ACKNOWLEDGE / STOP REPEATING SOUND");
        btnAckStop.setBackgroundColor(Color.rgb(180, 30, 30));
        btnAckStop.setTextColor(Color.WHITE);
        btnAckStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AlertPlayerService.stopPlayback();
                Intent stopIntent = new Intent(MainActivity.this, AlertPlayerService.class);
                stopIntent.putExtra("action", "STOP_ACK");
                startService(stopIntent);
                Toast.makeText(MainActivity.this, "Sound Acknowledged & Stopped", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnAckStop);

        // 2. SYSTEM & DEVICE ADMIN CONTROLS
        addHeader(root, "--- System & Device Admin Setup ---");

        cbMonitorEnabled = new CheckBox(this);
        cbMonitorEnabled.setText("Enable Notification Sound Monitoring");
        cbMonitorEnabled.setChecked(store.monitorEnabled);
        root.addView(cbMonitorEnabled);

        cbRunOnStart = new CheckBox(this);
        cbRunOnStart.setText("Run on phone start (Boot)");
        cbRunOnStart.setChecked(store.runOnStart);
        root.addView(cbRunOnStart);

        LinearLayout sysBtnRow = new LinearLayout(this);
        sysBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnEnableAccess = new Button(this);
        btnEnableAccess.setText("1. Enable Monitor");
        btnEnableAccess.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    Toast.makeText(MainActivity.this, "Enable 'NotifSounder Monitor'", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Open Settings -> Accessibility", Toast.LENGTH_LONG).show();
                }
            }
        });
        sysBtnRow.addView(btnEnableAccess, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnDeviceAdmin = new Button(this);
        btnDeviceAdmin.setText("2. Device Admin");
        btnDeviceAdmin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                requestDeviceAdmin();
            }
        });
        sysBtnRow.addView(btnDeviceAdmin, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        if (Build.VERSION.SDK_INT >= 23) {
            Button btnDndPerm = new Button(this);
            btnDndPerm.setText("3. DND Access");
            btnDndPerm.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        startActivity(new Intent("android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"));
                    } catch (Exception ignored) {}
                }
            });
            sysBtnRow.addView(btnDndPerm, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        root.addView(sysBtnRow);

        addSeparator(root);

        // 3. SOUND PROFILES SECTION
        addHeader(root, "--- Sound Profiles (MP3 / MIDI / Vol / DND / Vib) ---");

        LinearLayout profSelectRow = new LinearLayout(this);
        profSelectRow.setOrientation(LinearLayout.HORIZONTAL);

        spProfiles = new Spinner(this);
        profileSpinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, profileNames);
        profileSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProfiles.setAdapter(profileSpinnerAdapter);
        profSelectRow.addView(spProfiles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnNewProf = new Button(this);
        btnNewProf.setText("+ New Profile");
        btnNewProf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentProfileFromUI();
                ConfigStore.SoundProfile np = new ConfigStore.SoundProfile();
                np.name = "Profile " + (store.profiles.size() + 1);
                store.profiles.add(np);
                refreshProfileSpinners(store.profiles.size() - 1);
                store.save(MainActivity.this);
            }
        });
        profSelectRow.addView(btnNewProf);

        Button btnDelProf = new Button(this);
        btnDelProf.setText("Del");
        btnDelProf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int idx = spProfiles.getSelectedItemPosition();
                if (store.profiles.size() > 1 && idx >= 0 && idx < store.profiles.size()) {
                    store.profiles.remove(idx);
                    refreshProfileSpinners(0);
                    store.save(MainActivity.this);
                }
            }
        });
        profSelectRow.addView(btnDelProf);
        root.addView(profSelectRow);

        etProfName = addLabeledInput(root, "Profile Name:", "Default");

        TextView lblSound = new TextView(this);
        lblSound.setText("Sound File (.mp3, .mid, .midi, .ogg, .wav):");
        root.addView(lblSound);

        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setOrientation(LinearLayout.HORIZONTAL);
        etProfSoundPath = new EditText(this);
        etProfSoundPath.setSingleLine(true);
        etProfSoundPath.setHint("/sdcard/alert.mp3 or .mid");
        soundRow.addView(etProfSoundPath, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnBrowseSound = new Button(this);
        btnBrowseSound.setText("Browse...");
        btnBrowseSound.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showFileBrowser(Environment.getExternalStorageDirectory(), false);
            }
        });
        soundRow.addView(btnBrowseSound);

        Button btnTestProf = new Button(this);
        btnTestProf.setText("Test");
        btnTestProf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentProfileFromUI();
                store.save(MainActivity.this);
                int idx = spProfiles.getSelectedItemPosition();
                if (idx >= 0 && idx < store.profiles.size()) {
                    ConfigStore.SoundProfile p = store.profiles.get(idx);
                    Intent testIntent = new Intent(MainActivity.this, AlertPlayerService.class);
                    testIntent.putExtra("action", "PLAY");
                    testIntent.putExtra("sound_path", p.soundPath);
                    testIntent.putExtra("volume", p.volumePercent);
                    testIntent.putExtra("ignore_dnd", p.ignoreDnd);
                    testIntent.putExtra("vibrate", p.vibrate);
                    testIntent.putExtra("repeat_ack", false);
                    testIntent.putExtra("rule_name", "Test Profile: " + p.name);
                    BootReceiver.startServiceCompat(MainActivity.this, testIntent);
                }
            }
        });
        soundRow.addView(btnTestProf);
        root.addView(soundRow);

        etProfVolume = addLabeledInput(root, "Volume Level (0 - 100%):", "100");

        cbProfIgnoreDnd = new CheckBox(this);
        cbProfIgnoreDnd.setText("Ignore Do Not Disturb (DND) Mode");
        root.addView(cbProfIgnoreDnd);

        cbProfVibrate = new CheckBox(this);
        cbProfVibrate.setText("Vibrate on Alert");
        cbProfVibrate.setChecked(true);
        root.addView(cbProfVibrate);

        addSeparator(root);

        // 4. UNLIMITED NOTIFICATION EVENTS SECTION
        addHeader(root, "--- Notification Events (Unlimited Rules) ---");

        LinearLayout evSelectRow = new LinearLayout(this);
        evSelectRow.setOrientation(LinearLayout.HORIZONTAL);

        spEvents = new Spinner(this);
        eventSpinnerAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, eventLabels);
        eventSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEvents.setAdapter(eventSpinnerAdapter);
        evSelectRow.addView(spEvents, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnAddEvent = new Button(this);
        btnAddEvent.setText("+ Add Event");
        btnAddEvent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentEventFromUI();
                ConfigStore.NotifEvent ne = new ConfigStore.NotifEvent();
                ne.name = "Event " + (store.events.size() + 1);
                ne.appFilter = "*";
                ne.matchText = "urgent";
                ne.profileName = store.profiles.get(0).name;
                ne.repeatUntilAck = false;
                ne.luaScriptPath = "";
                store.events.add(ne);
                refreshEventSpinner(store.events.size() - 1);
                store.save(MainActivity.this);
            }
        });
        evSelectRow.addView(btnAddEvent);

        Button btnDelEvent = new Button(this);
        btnDelEvent.setText("Del");
        btnDelEvent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int idx = spEvents.getSelectedItemPosition();
                if (idx >= 0 && idx < store.events.size()) {
                    store.events.remove(idx);
                    refreshEventSpinner(0);
                    store.save(MainActivity.this);
                }
            }
        });
        evSelectRow.addView(btnDelEvent);
        root.addView(evSelectRow);

        cbEvEnabled = new CheckBox(this);
        cbEvEnabled.setText("Event Rule Enabled");
        cbEvEnabled.setChecked(true);
        root.addView(cbEvEnabled);

        etEvName = addLabeledInput(root, "Event Name:", "Rule 1");

        TextView lblApp = new TextView(this);
        lblApp.setText("Filter by App Package ('*' for any app, or pick app):");
        root.addView(lblApp);

        LinearLayout appFilterRow = new LinearLayout(this);
        appFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        etEvAppFilter = new EditText(this);
        etEvAppFilter.setSingleLine(true);
        etEvAppFilter.setText("*");
        appFilterRow.addView(etEvAppFilter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnPickApp = new Button(this);
        btnPickApp.setText("Pick App...");
        btnPickApp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showInstalledAppsPicker();
            }
        });
        appFilterRow.addView(btnPickApp);
        root.addView(appFilterRow);

        etEvMatchText = addLabeledInput(root, "Notification Text to Match (case-insensitive):", "");

        TextView lblEvProf = new TextView(this);
        lblEvProf.setText("Sound Profile for this Event:");
        root.addView(lblEvProf);

        spEvProfile = new Spinner(this);
        spEvProfile.setAdapter(profileSpinnerAdapter);
        root.addView(spEvProfile);

        // Optional LuaJ Script per Event
        TextView lblLua = new TextView(this);
        lblLua.setText("Optional LuaJ Script (.lua) or Command to run on Event:");
        root.addView(lblLua);

        LinearLayout luaRow = new LinearLayout(this);
        luaRow.setOrientation(LinearLayout.HORIZONTAL);
        etEvLuaScript = new EditText(this);
        etEvLuaScript.setSingleLine(true);
        etEvLuaScript.setHint("/sdcard/on_event.lua (optional)");
        luaRow.addView(etEvLuaScript, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnBrowseLua = new Button(this);
        btnBrowseLua.setText("Browse .lua");
        btnBrowseLua.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showFileBrowser(Environment.getExternalStorageDirectory(), true);
            }
        });
        luaRow.addView(btnBrowseLua);

        Button btnTestLua = new Button(this);
        btnTestLua.setText("Test Lua");
        btnTestLua.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveCurrentEventFromUI();
                store.save(MainActivity.this);
                final String scriptPath = etEvLuaScript.getText().toString().trim();
                if (scriptPath.length() == 0) {
                    Toast.makeText(MainActivity.this, "No Lua script specified for this event", Toast.LENGTH_SHORT).show();
                    return;
                }
                tvStatus.setText("Running script: " + scriptPath + " ...");
                AlertPlayerService.runEventScriptAsync(
                        scriptPath,
                        etEvAppFilter.getText().toString().trim(),
                        etEvMatchText.getText().toString().trim(),
                        etEvName.getText().toString().trim(),
                        spEvProfile.getSelectedItem() != null ? spEvProfile.getSelectedItem().toString() : "Default"
                );
                v.postDelayed(new Runnable() {
                    public void run() {
                        tvStatus.setText(AlertPlayerService.getLastLuaResult());
                    }
                }, 600);
            }
        });
        luaRow.addView(btnTestLua);
        root.addView(luaRow);

        cbEvRepeatAck = new CheckBox(this);
        cbEvRepeatAck.setText("Replay sound over and over until acknowledged");
        cbEvRepeatAck.setChecked(false); // Disabled by default
        root.addView(cbEvRepeatAck);

        addSeparator(root);

        // 5. SAVE ALL BUTTON & STATUS CONSOLE
        Button btnSaveAll = new Button(this);
        btnSaveAll.setText("SAVE ALL PROFILES & EVENTS");
        btnSaveAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                store.monitorEnabled = cbMonitorEnabled.isChecked();
                store.runOnStart = cbRunOnStart.isChecked();
                saveCurrentProfileFromUI();
                saveCurrentEventFromUI();
                store.save(MainActivity.this);
                refreshProfileSpinners(spProfiles.getSelectedItemPosition());
                refreshEventSpinner(spEvents.getSelectedItemPosition());
                Toast.makeText(MainActivity.this, "Saved " + store.profiles.size()
                        + " profile(s) and " + store.events.size() + " event(s)", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnSaveAll);

        tvStatus = new TextView(this);
        tvStatus.setPadding(4, 10, 4, 10);
        tvStatus.setText("Ready. Lua globals injected on match: NOTIF_PKG, NOTIF_TEXT, EVENT_NAME, PROFILE_NAME");
        root.addView(tvStatus);

        scroll.addView(root);
        setContentView(scroll);

        try {
            getWindow().setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, android.R.drawable.ic_popup_sync);
        } catch (Throwable ignored) {}

        spProfiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingProfileUI) return;
                loadProfileToUI(position);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        spEvents.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingEventUI) return;
                loadEventToUI(position);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (store.events.isEmpty()) {
            ConfigStore.NotifEvent defEv = new ConfigStore.NotifEvent();
            defEv.name = "Default Event";
            defEv.appFilter = "*";
            defEv.matchText = "alert";
            defEv.profileName = store.profiles.get(0).name;
            defEv.repeatUntilAck = false;
            defEv.luaScriptPath = "";
            store.events.add(defEv);
        }

        refreshProfileSpinners(0);
        refreshEventSpinner(0);
    }

    private void refreshProfileSpinners(int selectIdx) {
        updatingProfileUI = true;
        profileNames.clear();
        for (int i = 0; i < store.profiles.size(); i++) {
            profileNames.add(store.profiles.get(i).name);
        }
        profileSpinnerAdapter.notifyDataSetChanged();
        if (selectIdx >= 0 && selectIdx < store.profiles.size()) {
            spProfiles.setSelection(selectIdx);
            loadProfileToUI(selectIdx);
        }
        updatingProfileUI = false;
    }

    private void loadProfileToUI(int idx) {
        if (idx < 0 || idx >= store.profiles.size()) return;
        updatingProfileUI = true;
        ConfigStore.SoundProfile p = store.profiles.get(idx);
        etProfName.setText(p.name);
        etProfSoundPath.setText(p.soundPath);
        etProfVolume.setText(String.valueOf(p.volumePercent));
        cbProfIgnoreDnd.setChecked(p.ignoreDnd);
        cbProfVibrate.setChecked(p.vibrate);
        updatingProfileUI = false;
    }

    private void saveCurrentProfileFromUI() {
        int idx = spProfiles.getSelectedItemPosition();
        if (idx < 0 || idx >= store.profiles.size()) return;
        ConfigStore.SoundProfile p = store.profiles.get(idx);
        p.name = etProfName.getText().toString().trim();
        if (p.name.length() == 0) p.name = "Profile " + (idx + 1);
        p.soundPath = etProfSoundPath.getText().toString().trim();
        try {
            p.volumePercent = Integer.parseInt(etProfVolume.getText().toString().trim());
        } catch (Exception e) {
            p.volumePercent = 100;
        }
        p.ignoreDnd = cbProfIgnoreDnd.isChecked();
        p.vibrate = cbProfVibrate.isChecked();
    }

    private void refreshEventSpinner(int selectIdx) {
        updatingEventUI = true;
        eventLabels.clear();
        for (int i = 0; i < store.events.size(); i++) {
            ConfigStore.NotifEvent ev = store.events.get(i);
            eventLabels.add((i + 1) + ". " + ev.name + " [" + ev.matchText + "]");
        }
        eventSpinnerAdapter.notifyDataSetChanged();
        if (selectIdx >= 0 && selectIdx < store.events.size()) {
            spEvents.setSelection(selectIdx);
            loadEventToUI(selectIdx);
        }
        updatingEventUI = false;
    }

    private void loadEventToUI(int idx) {
        if (idx < 0 || idx >= store.events.size()) return;
        updatingEventUI = true;
        ConfigStore.NotifEvent ev = store.events.get(idx);
        cbEvEnabled.setChecked(ev.enabled);
        etEvName.setText(ev.name);
        etEvAppFilter.setText(ev.appFilter);
        etEvMatchText.setText(ev.matchText);
        etEvLuaScript.setText(ev.luaScriptPath != null ? ev.luaScriptPath : "");
        cbEvRepeatAck.setChecked(ev.repeatUntilAck);
        int profIdx = profileNames.indexOf(ev.profileName);
        if (profIdx >= 0) spEvProfile.setSelection(profIdx);
        updatingEventUI = false;
    }

    private void saveCurrentEventFromUI() {
        int idx = spEvents.getSelectedItemPosition();
        if (idx < 0 || idx >= store.events.size()) return;
        ConfigStore.NotifEvent ev = store.events.get(idx);
        ev.enabled = cbEvEnabled.isChecked();
        ev.name = etEvName.getText().toString().trim();
        if (ev.name.length() == 0) ev.name = "Event " + (idx + 1);
        ev.appFilter = etEvAppFilter.getText().toString().trim();
        if (ev.appFilter.length() == 0) ev.appFilter = "*";
        ev.matchText = etEvMatchText.getText().toString().trim();
        ev.luaScriptPath = etEvLuaScript.getText().toString().trim();
        Object selProf = spEvProfile.getSelectedItem();
        ev.profileName = selProf != null ? selProf.toString() : store.profiles.get(0).name;
        ev.repeatUntilAck = cbEvRepeatAck.isChecked();
    }

    // =========================================================================
    // FILE BROWSER (MP3/MIDI OR LUA SCRIPTS) & APP PICKER & DEVICE ADMIN
    // =========================================================================

    private void showFileBrowser(final File dir, final boolean forLuaScript) {
        File[] files = null;
        try { if (dir != null) files = dir.listFiles(); } catch (Throwable ignored) {}

        final List<File> entries = new ArrayList<File>();
        final List<String> labels = new ArrayList<String>();

        if (dir != null && dir.getParentFile() != null) {
            entries.add(dir.getParentFile());
            labels.add(".. (Parent Directory)");
        }

        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    entries.add(files[i]);
                    labels.add("[" + files[i].getName() + "]");
                }
            }
            for (int i = 0; i < files.length; i++) {
                if (!files[i].isDirectory()) {
                    String n = files[i].getName().toLowerCase();
                    if (forLuaScript) {
                        if (n.endsWith(".lua") || n.endsWith(".sh")) {
                            entries.add(files[i]);
                            labels.add(files[i].getName());
                        }
                    } else {
                        if (n.endsWith(".mp3") || n.endsWith(".mid") || n.endsWith(".midi")
                                || n.endsWith(".ogg") || n.endsWith(".wav")) {
                            entries.add(files[i]);
                            labels.add(files[i].getName());
                        }
                    }
                }
            }
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle((forLuaScript ? "Select .lua: " : "Select Audio: ") + (dir != null ? dir.getAbsolutePath() : "/"));
        b.setItems(labels.toArray(new String[labels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                File target = entries.get(which);
                if (target.isDirectory()) {
                    showFileBrowser(target, forLuaScript);
                } else {
                    if (forLuaScript) {
                        etEvLuaScript.setText(target.getAbsolutePath());
                        saveCurrentEventFromUI();
                    } else {
                        etProfSoundPath.setText(target.getAbsolutePath());
                        saveCurrentProfileFromUI();
                    }
                    store.save(MainActivity.this);
                }
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void showInstalledAppsPicker() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        final List<String> pkgNames = new ArrayList<String>();
        final List<String> displayLabels = new ArrayList<String>();

        pkgNames.add("*");
        displayLabels.add("* (All Apps)");

        List<String> sorted = new ArrayList<String>();
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo ai = apps.get(i);
            String label = String.valueOf(pm.getApplicationLabel(ai));
            sorted.add(label + " (" + ai.packageName + ")|" + ai.packageName);
        }
        Collections.sort(sorted);

        for (int i = 0; i < sorted.size(); i++) {
            String s = sorted.get(i);
            int pipe = s.lastIndexOf('|');
            displayLabels.add(s.substring(0, pipe));
            pkgNames.add(s.substring(pipe + 1));
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Filter by Notification App");
        b.setItems(displayLabels.toArray(new String[displayLabels.size()]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                etEvAppFilter.setText(pkgNames.get(which));
                saveCurrentEventFromUI();
                store.save(MainActivity.this);
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void requestDeviceAdmin() {
        if (Build.VERSION.SDK_INT < 8) {
            Toast.makeText(this, "Device Admin API requires Android 2.2+", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ComponentName comp = new ComponentName(this, AdminReceiver.class);
            Intent intent = new Intent("android.app.action.ADD_DEVICE_ADMIN");
            intent.putExtra("android.app.extra.DEVICE_ADMIN", comp);
            intent.putExtra("android.app.extra.ADD_EXPLANATION",
                    "Keeps NotifSounder active in memory to monitor notifications reliably.");
            startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(this, "Unable to open Device Admin screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void ensureModernPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Method isMgr = Environment.class.getMethod("isExternalStorageManager");
                Boolean granted = (Boolean) isMgr.invoke(null);
                if (granted != null && !granted.booleanValue()) {
                    Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                Method checkSelf = Activity.class.getMethod("checkSelfPermission", String.class);
                Integer res = (Integer) checkSelf.invoke(this, "android.permission.READ_EXTERNAL_STORAGE");
                if (res != null && res.intValue() != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerms = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[] {
                            "android.permission.READ_EXTERNAL_STORAGE",
                            "android.permission.WRITE_EXTERNAL_STORAGE"
                    }, Integer.valueOf(101));
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Method checkSelf = Activity.class.getMethod("checkSelfPermission", String.class);
                Integer res = (Integer) checkSelf.invoke(this, "android.permission.POST_NOTIFICATIONS");
                if (res != null && res.intValue() != PackageManager.PERMISSION_GRANTED) {
                    Method reqPerms = Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[] { "android.permission.POST_NOTIFICATIONS" }, Integer.valueOf(102));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 8, 0, 4);
        parent.addView(tv);
    }

    private EditText addLabeledInput(LinearLayout parent, String label, String defVal) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(defVal);
        parent.addView(et);
        return et;
    }

    private void addSeparator(LinearLayout parent) {
        View sep = new View(this);
        sep.setBackgroundColor(Color.DKGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2);
        lp.setMargins(0, 14, 0, 14);
        parent.addView(sep, lp);
    }
}
