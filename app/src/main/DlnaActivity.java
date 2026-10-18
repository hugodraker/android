package com.example.dlnaserver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class DlnaActivity extends Activity {

    private Button btnToggle;
    private EditText editPort;
    private TextView textLog;
    private boolean isServerRunning = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Simplified programmatic layout for Android 2.3
        setContentView(R.layout.main); 

        btnToggle = (Button) findViewById(R.id.btnToggle);
        editPort = (EditText) findViewById(R.id.editPort);
        textLog = (TextView) findViewById(R.id.textLog);

        editPort.setText(String.valueOf(DlnaService.DEFAULT_DLNA_PORT));

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(DlnaActivity.this, DlnaService.class);
                if (!isServerRunning) {
                    int port = Integer.parseInt(editPort.getText().toString());
                    serviceIntent.putExtra("PORT", port);
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
}
