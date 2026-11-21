package com.example.asyncer;

import android.app.Activity;
import android.os.Bundle;

public class LuaDialogActivity extends Activity {

    public interface DialogHostCallback {
        void onHostReady(Activity hostActivity);
    }

    private static volatile DialogHostCallback pendingCallback;

    public static void setPendingCallback(DialogHostCallback cb) {
        pendingCallback = cb;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DialogHostCallback cb = pendingCallback;
        pendingCallback = null;
        if (cb != null) {
            cb.onHostReady(this);
        } else {
            finish();
        }
    }
}