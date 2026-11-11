package com.example.asyncer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("AsyncerPrefs", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
            }, 1);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scroll.addView(layout);

        // --- Global Settings ---
        layout.addView(createLabel("Log File Path:"));
        EditText editLog = createInput(layout, prefs.getString("LogFile", "/sdcard/asyncer.log"));
        
        layout.addView(createLabel("Refresh Interval (s):"));
        EditText editRefresh = createInput(layout, String.valueOf(prefs.getInt("RefreshInterval", 3600)));

        // --- Jobs Section ---
        layout.addView(createLabel("\nAdd New Job:"));
        layout.addView(createLabel("Source Path:"));
        EditText editSrc = createInput(layout, "/sdcard/Documents");
        
        layout.addView(createLabel("Dest Path:"));
        EditText editDst = createInput(layout, "/sdcard/Backup");
        
        layout.addView(createLabel("Flags (Bitmask):"));
        EditText editFlags = createInput(layout, "16"); // Default to SYNC_ALWAYS
        
        layout.addView(createLabel("Masks (*.*):"));
        EditText editMasks = createInput(layout, "*.*");

        Button btnSaveJob = new Button(this);
        btnSaveJob.setText("Save Job & Settings");
        btnSaveJob.setOnClickListener(v -> {
            // Save Settings
            prefs.edit()
                 .putString("LogFile", editLog.getText().toString())
                 .putInt("RefreshInterval", Integer.parseInt(editRefresh.getText().toString()))
                 .apply();

            // Save Job (Format: Src,Dst,Flags,Masks,PostCmd)[cite: 1]
            String src = editSrc.getText().toString().trim();
            String dst = editDst.getText().toString().trim();
            if (!src.isEmpty() && !dst.isEmpty()) {
                String newJob = String.format("%s,%s,%s,%s,", src, dst, editFlags.getText().toString(), editMasks.getText().toString());
                String existingJobs = prefs.getString("Jobs", "");
                prefs.edit().putString("Jobs", existingJobs + newJob + ";;").apply();
                Toast.makeText(this, "Job Saved", Toast.LENGTH_SHORT).show();
            }

            // Restart Service to apply
            Intent serviceIntent = new Intent(this, AsyncerService.class);
            stopService(serviceIntent);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
            else startService(serviceIntent);
        });
        layout.addView(btnSaveJob);

        Button btnClearJobs = new Button(this);
        btnClearJobs.setText("Clear All Jobs");
        btnClearJobs.setOnClickListener(v -> {
            prefs.edit().putString("Jobs", "").apply();
            Toast.makeText(this, "All Jobs Cleared", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnClearJobs);

        setContentView(scroll);

        // Auto-start tracker on open
        Intent serviceIntent = new Intent(this, AsyncerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    private TextView createLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 10, 0, 0);
        return tv;
    }

    private EditText createInput(LinearLayout parent, String defVal) {
        EditText et = new EditText(this);
        et.setText(defVal);
        parent.addView(et);
        return et;
    }
}
