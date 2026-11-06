package com.hugo.caldavd;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.security.KeyChain;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private EditText editPort, editHttpPort, editTimeout, editUser, editPass;
    private CheckBox chkAnon, chkTls, chkEditAll;
    private Button btnStart, btnStop;
    private ListView listUsers;
    private ArrayAdapter<String> usersAdapter;
    private final ArrayList<String> usersList = new ArrayList<>();
    private String selectedUser = null;
    private boolean ignoreTextChanges = false;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable debounceSaveRunnable = this::savePortAndTimeoutConfig;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStartStopButtons();
        }
    };

    private final ActivityResultLauncher<Intent> exportPfxLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    File tempPfx = new File(getCacheDir(), "CalDAV_CertBundle.pfx");
                    if (CaldavService.nativeExportPfx(tempPfx.getAbsolutePath())) {
                        try (InputStream in = new FileInputStream(tempPfx);
                             OutputStream out = getContentResolver().openOutputStream(uri)) {
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                            Toast.makeText(this, "PFX bundle exported successfully.\nImport password is: caldav", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Failed to write PFX file.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "Failed to generate certificate chain.", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> importIcsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\r\n");
                        int count = CaldavService.nativeImportIcs(sb.toString());
                        new AlertDialog.Builder(this)
                                .setTitle("CaldAV-D")
                                .setMessage("Successfully imported " + count + " events from ICS.")
                                .setPositiveButton("OK", null)
                                .show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to import ICS: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> exportIcsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    String ics = CaldavService.nativeExportIcs();
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write(ics.getBytes());
                        new AlertDialog.Builder(this)
                                .setTitle("CaldAV-D")
                                .setMessage("Calendar exported to ICS successfully.")
                                .setPositiveButton("OK", null)
                                .show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to export ICS.", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("CalDAV TLS Settings");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        CaldavService.nativeInit(getFilesDir().getAbsolutePath());
        buildWin32FaithfulGui();
        loadValuesFromNative();
        populateUserList();

        if (!CaldavService.nativeIsRunning()) {
            Intent svc = new Intent(this, CaldavService.class);
            svc.setAction(CaldavService.ACTION_START);
            ContextCompat.startForegroundService(this, svc);
        }
    }

    private void buildWin32FaithfulGui() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Top section: Left column (Ports, Timeout, Checkboxes) + Right column (Action Buttons)
        LinearLayout topColumns = new LinearLayout(this);
        topColumns.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        leftCol.setLayoutParams(leftParams);

        editPort = addLabeledNumberRow(leftCol, "CalDAV Port (>1000):");
        editHttpPort = addLabeledNumberRow(leftCol, "HTTP Port (>1000):");
        editTimeout = addLabeledNumberRow(leftCol, "Timeout (ms):");

        chkAnon = new CheckBox(this);
        chkAnon.setText("Anonymous Access");
        leftCol.addView(chkAnon);

        chkTls = new CheckBox(this);
        chkTls.setText("Enable TLS/SSL");
        leftCol.addView(chkTls);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        rightParams.setMarginStart(dp(12));
        rightCol.setLayoutParams(rightParams);

        btnStart = new Button(this);
        btnStart.setText("Start");
        rightCol.addView(btnStart);

        btnStop = new Button(this);
        btnStop.setText("Stop");
        rightCol.addView(btnStop);

        Button btnTrustCert = new Button(this);
        btnTrustCert.setText("Trust Cert");
        rightCol.addView(btnTrustCert);

        Button btnExportCert = new Button(this);
        btnExportCert.setText("Export Cert");
        rightCol.addView(btnExportCert);

        Button btnManageCert = new Button(this);
        btnManageCert.setText("Manage Cert");
        rightCol.addView(btnManageCert);

        topColumns.addView(leftCol);
        topColumns.addView(rightCol);
        root.addView(topColumns);

        // Users Section (mirrors Win32 ListBox + Username/Password/CanEditAll/Save/ResetPw)
        TextView lblUsers = new TextView(this);
        lblUsers.setText("Users (Tap to select, Long-press to Delete):");
        lblUsers.setPadding(0, dp(16), 0, dp(6));
        root.addView(lblUsers);

        LinearLayout bottomColumns = new LinearLayout(this);
        bottomColumns.setOrientation(LinearLayout.HORIZONTAL);

        listUsers = new ListView(this);
        usersAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, usersList);
        listUsers.setAdapter(usersAdapter);
        listUsers.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listUsers.setBackgroundResource(android.R.drawable.editbox_background);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(0, dp(230), 1.0f);
        listUsers.setLayoutParams(listParams);

        LinearLayout userFormCol = new LinearLayout(this);
        userFormCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        formParams.setMarginStart(dp(12));
        userFormCol.setLayoutParams(formParams);

        TextView lblU = new TextView(this);
        lblU.setText("Username:");
        userFormCol.addView(lblU);
        editUser = new EditText(this);
        editUser.setSingleLine(true);
        userFormCol.addView(editUser);

        TextView lblP = new TextView(this);
        lblP.setText("Password:");
        userFormCol.addView(lblP);
        editPass = new EditText(this);
        editPass.setSingleLine(true);
        editPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        userFormCol.addView(editPass);

        chkEditAll = new CheckBox(this);
        chkEditAll.setText("Can edit all events");
        userFormCol.addView(chkEditAll);

        LinearLayout userBtnRow = new LinearLayout(this);
        userBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSaveUser = new Button(this);
        btnSaveUser.setText("Save User");
        Button btnResetPw = new Button(this);
        btnResetPw.setText("Reset Pw");
        userBtnRow.addView(btnSaveUser);
        userBtnRow.addView(btnResetPw);
        userFormCol.addView(userBtnRow);

        Button btnDeleteUser = new Button(this);
        btnDeleteUser.setText("Delete Selected User");
        userFormCol.addView(btnDeleteUser);

        bottomColumns.addView(listUsers);
        bottomColumns.addView(userFormCol);
        root.addView(bottomColumns);

        // Tray Menu Equivalent Section (Import ICS, Export ICS, Open Web UI)
        TextView lblTray = new TextView(this);
        lblTray.setText("Tray Menu Actions:");
        lblTray.setPadding(0, dp(16), 0, dp(6));
        root.addView(lblTray);

        LinearLayout trayRow = new LinearLayout(this);
        trayRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnImportIcs = new Button(this);
        btnImportIcs.setText("Import .ics");
        Button btnExportIcs = new Button(this);
        btnExportIcs.setText("Export .ics");
        Button btnOpenWeb = new Button(this);
        btnOpenWeb.setText("Open Web UI");
        trayRow.addView(btnImportIcs);
        trayRow.addView(btnExportIcs);
        trayRow.addView(btnOpenWeb);
        root.addView(trayRow);

        scroll.addView(root);
        setContentView(scroll);

        // Wire Listeners matching SettingsWndProc
        TextWatcher portWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (ignoreTextChanges) return;
                debounceHandler.removeCallbacks(debounceSaveRunnable);
                debounceHandler.postDelayed(debounceSaveRunnable, 1000);
            }
        };
        editPort.addTextChangedListener(portWatcher);
        editHttpPort.addTextChangedListener(portWatcher);
        editTimeout.addTextChangedListener(portWatcher);

        chkAnon.setOnCheckedChangeListener((b, checked) -> savePortAndTimeoutConfig());
        chkTls.setOnCheckedChangeListener((b, checked) -> savePortAndTimeoutConfig());

        btnStart.setOnClickListener(v -> {
            if (!savePortAndTimeoutConfig()) return;
            Intent svc = new Intent(this, CaldavService.class);
            svc.setAction(CaldavService.ACTION_START);
            ContextCompat.startForegroundService(this, svc);
        });

        btnStop.setOnClickListener(v -> {
            Intent svc = new Intent(this, CaldavService.class);
            svc.setAction(CaldavService.ACTION_STOP);
            ContextCompat.startForegroundService(this, svc);
        });

        btnTrustCert.setOnClickListener(v -> trustRootCertificate());
        btnExportCert.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/x-pkcs12");
            intent.putExtra(Intent.EXTRA_TITLE, "CalDAV_CertBundle.pfx");
            exportPfxLauncher.launch(intent);
        });
        btnManageCert.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            startActivity(intent);
        });

        listUsers.setOnItemClickListener((parent, view, position, id) -> {
            selectedUser = usersList.get(position);
            editUser.setText(selectedUser);
            editPass.setText("");
            chkEditAll.setChecked(CaldavService.nativeGetUserCanEditAll(selectedUser));
        });

        listUsers.setOnItemLongClickListener((parent, view, position, id) -> {
            String u = usersList.get(position);
            CaldavService.nativeDeleteUser(u);
            populateUserList();
            Toast.makeText(this, "Deleted user: " + u, Toast.LENGTH_SHORT).show();
            return true;
        });

        btnDeleteUser.setOnClickListener(v -> {
            if (selectedUser != null) {
                CaldavService.nativeDeleteUser(selectedUser);
                selectedUser = null;
                editUser.setText("");
                editPass.setText("");
                populateUserList();
            } else {
                Toast.makeText(this, "Select a user first.", Toast.LENGTH_SHORT).show();
            }
        });

        btnSaveUser.setOnClickListener(v -> {
            String u = editUser.getText().toString().trim();
            String p = editPass.getText().toString();
            if (!u.isEmpty() && !p.isEmpty()) {
                CaldavService.nativeSaveUser(u, p, chkEditAll.isChecked());
                populateUserList();
                editUser.setText("");
                editPass.setText("");
                Toast.makeText(this, "User saved.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Enter both username and password.", Toast.LENGTH_SHORT).show();
            }
        });

        btnResetPw.setOnClickListener(v -> {
            if (selectedUser != null) {
                String newPass = CaldavService.nativeResetPassword(selectedUser);
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("CalDAV Password", newPass));
                new AlertDialog.Builder(this)
                        .setTitle("CaldAV-D")
                        .setMessage("New password copied to clipboard!\n\nPassword: " + newPass)
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("CaldAV-D")
                        .setMessage("Please select a user from the list first.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });

        btnImportIcs.setOnClickListener(v -> triggerImportIcs());
        btnExportIcs.setOnClickListener(v -> triggerExportIcs());
        btnOpenWeb.setOnClickListener(v -> {
            int[] cfg = CaldavService.nativeGetConfig();
            String scheme = (cfg[4] != 0) ? "https" : "http";
            String url = scheme + "://127.0.0.1:" + cfg[1] + "/";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
    }

    private EditText addLabeledNumberRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        row.addView(tv);
        row.addView(et);
        parent.addView(row);
        return et;
    }

    private void loadValuesFromNative() {
        ignoreTextChanges = true;
        int[] cfg = CaldavService.nativeGetConfig();
        editPort.setText(String.valueOf(cfg[0]));
        editHttpPort.setText(String.valueOf(cfg[1]));
        editTimeout.setText(String.valueOf(cfg[2]));
        chkAnon.setChecked(cfg[3] != 0);
        chkTls.setChecked(cfg[4] != 0);
        ignoreTextChanges = false;
        updateStartStopButtons();
    }

    private boolean savePortAndTimeoutConfig() {
        try {
            int p = Integer.parseInt(editPort.getText().toString().trim());
            int hp = Integer.parseInt(editHttpPort.getText().toString().trim());
            int to = Integer.parseInt(editTimeout.getText().toString().trim());
            if (p <= 1000 || p > 65535 || hp <= 1000 || hp > 65535) {
                Toast.makeText(this, "Ports must be above 1000 (1001 - 65535).", Toast.LENGTH_SHORT).show();
                return false;
            }
            CaldavService.nativeSaveConfig(p, hp, to, chkAnon.isChecked(), chkTls.isChecked());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void populateUserList() {
        usersList.clear();
        String[] u = CaldavService.nativeGetUsers();
        if (u != null) usersList.addAll(Arrays.asList(u));
        usersAdapter.notifyDataSetChanged();
    }

    private void updateStartStopButtons() {
        boolean running = CaldavService.nativeIsRunning();
        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
    }

    private void trustRootCertificate() {
        String caPath = CaldavService.nativeGenerateAndGetRootCaPath();
        if (caPath == null) {
            Toast.makeText(this, "Failed to generate Root CA.", Toast.LENGTH_SHORT).show();
            return;
        }
        try (FileInputStream fis = new FileInputStream(caPath)) {
            byte[] certBytes = new byte[(int) new File(caPath).length()];
            fis.read(certBytes);
            Intent installIntent = KeyChain.createInstallIntent();
            installIntent.putExtra(KeyChain.EXTRA_CERTIFICATE, certBytes);
            installIntent.putExtra(KeyChain.EXTRA_NAME, "CalDAV Local Root CA");
            startActivity(installIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Error launching certificate installer.", Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerImportIcs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        importIcsLauncher.launch(intent);
    }

    private void triggerExportIcs() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/calendar");
        intent.putExtra(Intent.EXTRA_TITLE, "calendar_export.ics");
        exportIcsLauncher.launch(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Start Server");
        menu.add(0, 2, 0, "Stop Server");
        menu.add(0, 3, 0, "Import Calendar (.ics)");
        menu.add(0, 4, 0, "Export Calendar (.ics)");
        menu.add(0, 5, 0, "Exit");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: btnStart.performClick(); return true;
            case 2: btnStop.performClick(); return true;
            case 3: triggerImportIcs(); return true;
            case 4: triggerExportIcs(); return true;
            case 5:
                Intent exitSvc = new Intent(this, CaldavService.class);
                exitSvc.setAction(CaldavService.ACTION_EXIT);
                startService(exitSvc);
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, statusReceiver, new IntentFilter(CaldavService.ACTION_STATUS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        updateStartStopButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
