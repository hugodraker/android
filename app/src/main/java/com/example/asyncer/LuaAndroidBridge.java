package com.example.asyncer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.text.Html;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class LuaAndroidBridge {

    private static TextToSpeech ttsEngine;
    private static volatile boolean ttsReady = false;
    private static MediaPlayer mediaPlayer;
    private static volatile WeakReference<Activity> activeActivityRef = new WeakReference<Activity>(null);

    public static void setCurrentActivity(Activity act) {
        if (act != null) {
            activeActivityRef = new WeakReference<Activity>(act);
        }
    }

    private static Activity resolveActivity(Activity passed) {
        if (passed != null && !passed.isFinishing()) return passed;
        Activity ref = activeActivityRef.get();
        if (ref != null && !ref.isFinishing()) return ref;
        return null;
    }

    private interface UiAction {
        void run(Activity uiActivity, boolean isTempHost) throws Exception;
    }

    private static boolean runOnUiActivity(final Context appCtx, final Activity passedActivity,
                                           final Handler mainHandler, final SyncRunner.LogCallback logger,
                                           final UiAction action) {
        final Activity existing = resolveActivity(passedActivity);
        if (existing != null) {
            mainHandler.post(new Runnable() {
                public void run() {
                    try {
                        action.run(existing, false);
                    } catch (Throwable t) {
                        if (logger != null) logger.onLog("[Lua GUI Error] " + t.toString());
                    }
                }
            });
            return true;
        }

        try {
            LuaDialogActivity.setPendingCallback(new LuaDialogActivity.DialogHostCallback() {
                public void onHostReady(Activity hostActivity) {
                    try {
                        action.run(hostActivity, true);
                    } catch (Throwable t) {
                        if (logger != null) logger.onLog("[Lua GUI Error] " + t.toString());
                        hostActivity.finish();
                    }
                }
            });
            Intent intent = new Intent(appCtx, LuaDialogActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            if (logger != null) logger.onLog("[Lua GUI Error] " + t.toString());
            return false;
        }
    }

    public static String runLuaFile(final Context ctx, final Activity activity, String luaPath,
                                    Map<String, String> jobContext, final SyncRunner.LogCallback logger) throws Exception {
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final Globals globals = JsePlatform.standardGlobals();

        globals.set("CONTEXT", CoerceJavaToLua.coerce(appCtx));
        Activity resolvedAct = resolveActivity(activity);
        if (resolvedAct != null) {
            globals.set("ACTIVITY", CoerceJavaToLua.coerce(resolvedAct));
        }

        if (jobContext != null) {
            String host = jobContext.get("host");
            String share = jobContext.get("remote_path");
            String localDir = jobContext.get("local_dir");
            if (host != null) globals.set("SMB_HOST", LuaValue.valueOf(host));
            if (share != null) globals.set("SMB_SHARE", LuaValue.valueOf(share));
            if (localDir != null) globals.set("LOCAL_DIR", LuaValue.valueOf(localDir));
        }

        // =====================================================================
        // 1. LOGGING & TIMING
        // =====================================================================
        globals.set("log", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if (logger != null) logger.onLog("[Lua] " + arg.tojstring());
                return LuaValue.NIL;
            }
        });

        globals.set("sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue msArg) {
                try { Thread.sleep(Math.max(0L, msArg.tolong())); } catch (InterruptedException ignored) {}
                return LuaValue.NIL;
            }
        });

        // =====================================================================
        // 2. ANDROID GUI DIALOGS, PICKERS, PROGRESS, HTML POPUP & DISPLAY
        // =====================================================================
        globals.set("toast", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue msgArg, LuaValue longArg) {
                final String msg = msgArg.tojstring();
                final int dur = longArg.toboolean() ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT;
                mainHandler.post(new Runnable() {
                    public void run() { Toast.makeText(appCtx, msg, dur).show(); }
                });
                return LuaValue.TRUE;
            }
        });

        // dialog(title, message) -> blocks until user taps OK so dialogs never vanish prematurely
        globals.set("dialog", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg) {
                final String title = titleArg.isnil() ? "Asyncer" : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        AlertDialog dlg = new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMessage(msg)
                                .setCancelable(true)
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .create();
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.FALSE;
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.TRUE;
            }
        });

        globals.set("confirm", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg) {
                final String title = titleArg.isnil() ? "Confirm" : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean result = new AtomicBoolean(false);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        AlertDialog dlg = new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMessage(msg)
                                .setCancelable(false)
                                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(true);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(false);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .create();
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.FALSE;
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.valueOf(result.get());
            }
        });

        globals.set("prompt", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg, LuaValue defArg) {
                final String title = titleArg.isnil() ? "Input" : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final String defVal = defArg.isnil() ? "" : defArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<String> result = new AtomicReference<String>(null);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        final EditText input = new EditText(act);
                        input.setText(defVal);
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMessage(msg)
                                .setView(input)
                                .setCancelable(false)
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(input.getText().toString());
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        result.set(null);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                String out = result.get();
                return out != null ? LuaValue.valueOf(out) : LuaValue.NIL;
            }
        });

        globals.set("choose", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                final String title = args.arg(1).isnil() ? "Select" : args.arg(1).tojstring();
                LuaTable tbl = args.arg(2).checktable();
                int len = tbl.length();
                final String[] items = new String[len];
                for (int i = 0; i < len; i++) items[i] = tbl.get(i + 1).tojstring();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicInteger chosen = new AtomicInteger(-1);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        chosen.set(which);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                    public void onCancel(DialogInterface d) {
                                        chosen.set(-1);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                int idx = chosen.get();
                if (idx < 0 || idx >= items.length) return LuaValue.NIL;
                return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(idx + 1), LuaValue.valueOf(items[idx]) });
            }
        });

        globals.set("multi_choose", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                final String title = args.arg(1).isnil() ? "Select Items" : args.arg(1).tojstring();
                LuaTable tbl = args.arg(2).checktable();
                LuaTable pre = args.arg(3).istable() ? args.arg(3).checktable() : null;
                int len = tbl.length();
                final String[] items = new String[len];
                final boolean[] checked = new boolean[len];
                for (int i = 0; i < len; i++) {
                    items[i] = tbl.get(i + 1).tojstring();
                    if (pre != null) checked[i] = pre.get(i + 1).toboolean();
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean confirmed = new AtomicBoolean(false);
                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        new AlertDialog.Builder(act)
                                .setTitle(title)
                                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                                        checked[which] = isChecked;
                                    }
                                })
                                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        confirmed.set(true);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        confirmed.set(false);
                                        if (isTemp) act.finish();
                                        latch.countDown();
                                    }
                                })
                                .show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                if (!confirmed.get()) return LuaValue.NIL;
                LuaTable idxTbl = new LuaTable();
                LuaTable lblTbl = new LuaTable();
                int outIdx = 1;
                for (int i = 0; i < len; i++) {
                    if (checked[i]) {
                        idxTbl.set(outIdx, LuaValue.valueOf(i + 1));
                        lblTbl.set(outIdx, LuaValue.valueOf(items[i]));
                        outIdx++;
                    }
                }
                return LuaValue.varargsOf(new LuaValue[] { idxTbl, lblTbl });
            }
        });

        globals.set("date_picker", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Calendar cal = Calendar.getInstance();
                final int initY = args.arg(1).isint() ? args.arg(1).toint() : cal.get(Calendar.YEAR);
                final int initM = args.arg(2).isint() ? (args.arg(2).toint() - 1) : cal.get(Calendar.MONTH);
                final int initD = args.arg(3).isint() ? args.arg(3).toint() : cal.get(Calendar.DAY_OF_MONTH);
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<int[]> picked = new AtomicReference<int[]>(null);

                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        DatePickerDialog dlg = new DatePickerDialog(act, new DatePickerDialog.OnDateSetListener() {
                            public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                                picked.set(new int[] { year, monthOfYear + 1, dayOfMonth });
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        }, initY, initM, initD);
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                int[] res = picked.get();
                if (res == null) return LuaValue.NIL;
                String formatted = String.format(Locale.US, "%04d-%02d-%02d", res[0], res[1], res[2]);
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(formatted), LuaValue.valueOf(res[0]), LuaValue.valueOf(res[1]), LuaValue.valueOf(res[2])
                });
            }
        });

        globals.set("time_picker", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Calendar cal = Calendar.getInstance();
                final int initH = args.arg(1).isint() ? args.arg(1).toint() : cal.get(Calendar.HOUR_OF_DAY);
                final int initMin = args.arg(2).isint() ? args.arg(2).toint() : cal.get(Calendar.MINUTE);
                final boolean is24 = args.arg(3).isnil() ? true : args.arg(3).toboolean();
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicReference<int[]> picked = new AtomicReference<int[]>(null);

                boolean launched = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        TimePickerDialog dlg = new TimePickerDialog(act, new TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                                picked.set(new int[] { hourOfDay, minute });
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        }, initH, initMin, is24);
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!launched) return LuaValue.NIL;
                try { latch.await(); } catch (InterruptedException ignored) {}
                int[] res = picked.get();
                if (res == null) return LuaValue.NIL;
                String formatted = String.format(Locale.US, "%02d:%02d", res[0], res[1]);
                return LuaValue.varargsOf(new LuaValue[] {
                        LuaValue.valueOf(formatted), LuaValue.valueOf(res[0]), LuaValue.valueOf(res[1])
                });
            }
        });

        globals.set("progress_dialog", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue msgArg) {
                final String title = titleArg.isnil() ? "Working..." : titleArg.tojstring();
                final String msg = msgArg.isnil() ? "" : msgArg.tojstring();
                final AtomicReference<ProgressDialog> dlgRef = new AtomicReference<ProgressDialog>(null);
                final AtomicReference<Activity> tempActRef = new AtomicReference<Activity>(null);

                runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(Activity act, boolean isTemp) {
                        if (isTemp) tempActRef.set(act);
                        ProgressDialog pd = new ProgressDialog(act);
                        pd.setTitle(title);
                        pd.setMessage(msg);
                        pd.setCancelable(false);
                        pd.show();
                        dlgRef.set(pd);
                    }
                });

                LuaTable ctrl = new LuaTable();
                ctrl.set("update", new OneArgFunction() {
                    @Override
                    public LuaValue call(LuaValue newMsg) {
                        final String m = newMsg.tojstring();
                        mainHandler.post(new Runnable() {
                            public void run() {
                                ProgressDialog pd = dlgRef.get();
                                if (pd != null && pd.isShowing()) pd.setMessage(m);
                            }
                        });
                        return LuaValue.NIL;
                    }
                });
                ctrl.set("close", new ZeroArgFunction() {
                    @Override
                    public LuaValue call() {
                        mainHandler.post(new Runnable() {
                            public void run() {
                                ProgressDialog pd = dlgRef.get();
                                if (pd != null && pd.isShowing()) pd.dismiss();
                                Activity temp = tempActRef.get();
                                if (temp != null && !temp.isFinishing()) temp.finish();
                            }
                        });
                        return LuaValue.NIL;
                    }
                });
                return ctrl;
            }
        });

        // html_dialog(title, htmlOrUrl) -> blocks until closed; renders HTML reliably with ScrollView + Html.fromHtml (or WebView for URLs)
        globals.set("html_dialog", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue contentArg) {
                final String title = titleArg.isnil() ? "Asyncer" : titleArg.tojstring();
                final String content = contentArg.tojstring();
                final CountDownLatch latch = new CountDownLatch(1);

                boolean ok = runOnUiActivity(appCtx, activity, mainHandler, logger, new UiAction() {
                    public void run(final Activity act, final boolean isTemp) {
                        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle(title);

                        if (content.startsWith("http://") || content.startsWith("https://") || content.startsWith("file://")) {
                            LinearLayout container = new LinearLayout(act);
                            container.setOrientation(LinearLayout.VERTICAL);
                            int minHeightPx = (int) (320 * act.getResources().getDisplayMetrics().density);
                            WebView wv = new WebView(act);
                            wv.getSettings().setJavaScriptEnabled(true);
                            wv.setMinimumHeight(minHeightPx);
                            container.addView(wv, new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, minHeightPx));
                            wv.loadUrl(content);
                            b.setView(container);
                        } else {
                            ScrollView sv = new ScrollView(act);
                            TextView tv = new TextView(act);
                            tv.setPadding(24, 16, 24, 16);
                            tv.setTextSize(15.0f);
                            tv.setText(Html.fromHtml(content));
                            sv.addView(tv);
                            b.setView(sv);
                        }

                        b.setPositiveButton("Close", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        AlertDialog dlg = b.create();
                        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                if (isTemp) act.finish();
                                latch.countDown();
                            }
                        });
                        dlg.show();
                    }
                });
                if (!ok) return LuaValue.FALSE;
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.TRUE;
            }
        });

        globals.set("screen_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
                LuaTable tbl = new LuaTable();
                tbl.set("width", LuaValue.valueOf(dm.widthPixels));
                tbl.set("height", LuaValue.valueOf(dm.heightPixels));
                tbl.set("density", LuaValue.valueOf(dm.density));
                tbl.set("dpi", LuaValue.valueOf(dm.densityDpi));
                return tbl;
            }
        });

        globals.set("keep_screen_on", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue onArg) {
                final Activity act = resolveActivity(activity);
                if (act == null) return LuaValue.FALSE;
                final boolean on = onArg.toboolean();
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (on) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
                return LuaValue.TRUE;
            }
        });

        globals.set("notify", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue idArg, LuaValue titleArg, LuaValue textArg) {
                int id = idArg.isnil() ? 500 : idArg.toint();
                String title = titleArg.isnil() ? "Asyncer" : titleArg.tojstring();
                String text = textArg.isnil() ? "" : textArg.tojstring();
                postNotificationCompat(appCtx, id, title, text);
                return LuaValue.TRUE;
            }
        });

        // =====================================================================
        // 3. HTTP / HTTPS & URL ENCODING
        // =====================================================================
        globals.set("download", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String urlStr = args.arg(1).checkjstring();
                String destPath = args.arg(2).checkjstring();
                boolean trustAll = args.arg(3).toboolean();
                InputStream in = null;
                OutputStream out = null;
                HttpURLConnection conn = null;
                try {
                    File destFile = new File(destPath);
                    if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
                        destFile.getParentFile().mkdirs();
                    }
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    if (trustAll && conn instanceof HttpsURLConnection) {
                        configureTrustAllSsl((HttpsURLConnection) conn);
                    }
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Asyncer-LuaJ/1.0");
                    int code = conn.getResponseCode();
                    if (code >= 400) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf("HTTP " + code) });
                    }
                    in = new BufferedInputStream(conn.getInputStream());
                    out = new BufferedOutputStream(new FileOutputStream(destFile));
                    byte[] buf = new byte[16384];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    out.flush();
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.TRUE, LuaValue.valueOf(total) });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }
            }
        });

        globals.set("http_get", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String urlStr = args.arg(1).checkjstring();
                LuaTable headers = args.arg(2).istable() ? args.arg(2).checktable() : null;
                boolean trustAll = args.arg(3).toboolean();
                return doHttpRequest("GET", urlStr, null, null, headers, trustAll);
            }
        });

        globals.set("http_post", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String urlStr = args.arg(1).checkjstring();
                String body = args.arg(2).isnil() ? "" : args.arg(2).tojstring();
                String ctype = args.arg(3).isnil() ? "application/x-www-form-urlencoded; charset=UTF-8" : args.arg(3).tojstring();
                LuaTable headers = args.arg(4).istable() ? args.arg(4).checktable() : null;
                boolean trustAll = args.arg(5).toboolean();
                return doHttpRequest("POST", urlStr, body, ctype, headers, trustAll);
            }
        });

        globals.set("http_request", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String method = args.arg(1).checkjstring().toUpperCase();
                String urlStr = args.arg(2).checkjstring();
                String body = args.arg(3).isnil() ? null : args.arg(3).tojstring();
                String ctype = args.arg(4).isnil() ? null : args.arg(4).tojstring();
                LuaTable headers = args.arg(5).istable() ? args.arg(5).checktable() : null;
                boolean trustAll = args.arg(6).toboolean();
                return doHttpRequest(method, urlStr, body, ctype, headers, trustAll);
            }
        });

        globals.set("url_encode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue s) {
                try { return LuaValue.valueOf(URLEncoder.encode(s.tojstring(), "UTF-8")); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("url_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue s) {
                try { return LuaValue.valueOf(URLDecoder.decode(s.tojstring(), "UTF-8")); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // =====================================================================
        // 4. FTP CLIENT FUNCTIONS
        // =====================================================================
        globals.set("ftp_upload", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).isint() ? args.arg(2).toint() : 21;
                String user = args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring();
                String pass = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
                String localPath = args.arg(5).checkjstring();
                String remotePath = args.arg(6).checkjstring();
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(host, port, user, pass)) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(ftp.getLastReply()) });
                    }
                    boolean ok = ftp.uploadFile(new File(localPath), remotePath);
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(ok), LuaValue.valueOf(ftp.getLastReply()) });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    ftp.disconnect();
                }
            }
        });

        globals.set("ftp_download", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).isint() ? args.arg(2).toint() : 21;
                String user = args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring();
                String pass = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
                String remotePath = args.arg(5).checkjstring();
                String localPath = args.arg(6).checkjstring();
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(host, port, user, pass)) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(ftp.getLastReply()) });
                    }
                    boolean ok = ftp.downloadFile(remotePath, new File(localPath));
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(ok), LuaValue.valueOf(ftp.getLastReply()) });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.FALSE, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    ftp.disconnect();
                }
            }
        });

        globals.set("ftp_list", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).isint() ? args.arg(2).toint() : 21;
                String user = args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring();
                String pass = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
                String remoteDir = args.arg(5).isnil() ? "/" : args.arg(5).tojstring();
                boolean rec = args.arg(6).toboolean();
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(host, port, user, pass)) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(ftp.getLastReply()) });
                    }
                    List<FtpClient.FtpItem> items = ftp.fetchRemoteDirContents(remoteDir, rec);
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < items.size(); i++) {
                        FtpClient.FtpItem it = items.get(i);
                        LuaTable row = new LuaTable();
                        row.set("name", LuaValue.valueOf(it.name));
                        row.set("path", LuaValue.valueOf(it.path));
                        row.set("is_dir", LuaValue.valueOf(it.isDirectory));
                        row.set("size", LuaValue.valueOf(it.size));
                        tbl.set(i + 1, row);
                    }
                    return LuaValue.varargsOf(new LuaValue[] { tbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    ftp.disconnect();
                }
            }
        });

        globals.set("ftp_delete", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).isint() ? args.arg(2).toint() : 21;
                String user = args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring();
                String pass = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
                String remotePath = args.arg(5).checkjstring();
                boolean isDir = args.arg(6).toboolean();
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(host, port, user, pass)) return LuaValue.FALSE;
                    return LuaValue.valueOf(ftp.deleteRemote(remotePath, isDir));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    ftp.disconnect();
                }
            }
        });

        globals.set("ftp_mkdir", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).isint() ? args.arg(2).toint() : 21;
                String user = args.arg(3).isnil() ? "anonymous" : args.arg(3).tojstring();
                String pass = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
                String remoteDir = args.arg(5).checkjstring();
                FtpClient ftp = new FtpClient();
                try {
                    if (!ftp.connect(host, port, user, pass)) return LuaValue.FALSE;
                    return LuaValue.valueOf(ftp.mkdirRecursive(remoteDir));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    ftp.disconnect();
                }
            }
        });

        // =====================================================================
        // 5. SMB CLIENT FUNCTIONS
        // =====================================================================
        globals.set("smb_shares", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String host = args.arg(1).checkjstring();
                int port = args.arg(2).isint() ? args.arg(2).toint() : 445;
                String user = args.arg(3).isnil() ? "guest" : args.arg(3).tojstring();
                String pass = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
                SmbClient smb = new SmbClient();
                try {
                    List<String> shares = smb.probeShares(host, port, user, pass);
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < shares.size(); i++) tbl.set(i + 1, LuaValue.valueOf(shares.get(i)));
                    return LuaValue.varargsOf(new LuaValue[] { tbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    smb.disconnect();
                }
            }
        });

        globals.set("smb_upload", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    SmbClient.ConnectionProfile p = buildSmbProfile(args);
                    if (!smb.connect(p)) return LuaValue.FALSE;
                    String localPath = args.arg(6).checkjstring();
                    String remoteRel = args.arg(7).checkjstring();
                    return LuaValue.valueOf(smb.copySingleL2r(localPath, remoteRel));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    smb.disconnect();
                }
            }
        });

        globals.set("smb_download", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    SmbClient.ConnectionProfile p = buildSmbProfile(args);
                    if (!smb.connect(p)) return LuaValue.FALSE;
                    String remoteRel = args.arg(6).checkjstring();
                    String localPath = args.arg(7).checkjstring();
                    return LuaValue.valueOf(smb.copySingleR2l(remoteRel, localPath));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    smb.disconnect();
                }
            }
        });

        globals.set("smb_list", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    SmbClient.ConnectionProfile p = buildSmbProfile(args);
                    if (!smb.connect(p)) {
                        return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf("SMB connect failed") });
                    }
                    String remoteDir = args.arg(6).isnil() ? "" : args.arg(6).tojstring();
                    List<SmbClient.DirectoryItem> items = smb.fetchRemoteDirContents(remoteDir);
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < items.size(); i++) {
                        SmbClient.DirectoryItem it = items.get(i);
                        LuaTable row = new LuaTable();
                        row.set("name", LuaValue.valueOf(it.name));
                        row.set("path", LuaValue.valueOf(it.path));
                        row.set("is_dir", LuaValue.valueOf(it.isDirectory));
                        row.set("size", LuaValue.valueOf(it.size));
                        row.set("last_modified", LuaValue.valueOf(it.getUnixTimeMs()));
                        tbl.set(i + 1, row);
                    }
                    return LuaValue.varargsOf(new LuaValue[] { tbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                } finally {
                    smb.disconnect();
                }
            }
        });

        globals.set("smb_delete", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    SmbClient.ConnectionProfile p = buildSmbProfile(args);
                    if (!smb.connect(p)) return LuaValue.FALSE;
                    return LuaValue.valueOf(smb.deleteRemote(args.arg(6).checkjstring(), false));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    smb.disconnect();
                }
            }
        });

        globals.set("smb_mkdir", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                SmbClient smb = new SmbClient();
                try {
                    SmbClient.ConnectionProfile p = buildSmbProfile(args);
                    if (!smb.connect(p)) return LuaValue.FALSE;
                    return LuaValue.valueOf(smb.mkdirRecursive(args.arg(6).checkjstring()));
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    smb.disconnect();
                }
            }
        });

        // =====================================================================
        // 6. TEXT-TO-SPEECH (SPEAK) & AUDIO PLAYBACK
        // =====================================================================
        globals.set("speak", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue textArg, LuaValue rateArg, LuaValue pitchArg) {
                final String text = textArg.checkjstring();
                final float rate = rateArg.isnumber() ? rateArg.tofloat() : 1.0f;
                final float pitch = pitchArg.isnumber() ? pitchArg.tofloat() : 1.0f;
                return LuaValue.valueOf(speakTextCompat(appCtx, mainHandler, text, rate, pitch));
            }
        });

        globals.set("stop_speak", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                try { if (ttsEngine != null) ttsEngine.stop(); } catch (Throwable ignored) {}
                return LuaValue.TRUE;
            }
        });

        globals.set("play_sound", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue srcArg, LuaValue loopArg) {
                String src = srcArg.checkjstring();
                boolean loop = loopArg.toboolean();
                return LuaValue.valueOf(playSoundCompat(appCtx, src, loop));
            }
        });

        globals.set("stop_sound", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                stopSoundCompat();
                return LuaValue.TRUE;
            }
        });

        globals.set("vibrate", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue msArg) {
                try {
                    Vibrator v = (Vibrator) appCtx.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(Math.max(10L, msArg.tolong()));
                    return LuaValue.TRUE;
                } catch (Throwable t) { return LuaValue.FALSE; }
            }
        });

        globals.set("beep", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue toneArg, LuaValue durArg) {
                try {
                    int tone = toneArg.isnil() ? ToneGenerator.TONE_PROP_BEEP : toneArg.toint();
                    int dur = durArg.isnil() ? 200 : durArg.toint();
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
                    tg.startTone(tone, dur);
                    return LuaValue.TRUE;
                } catch (Throwable t) { return LuaValue.FALSE; }
            }
        });

        // =====================================================================
        // 7. BINARY, BASE64, HEX & CRYPTOGRAPHIC HASH HELPERS
        // =====================================================================
        globals.set("read_binary", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                try {
                    byte[] data = readFileBytes(new File(pathArg.checkjstring()));
                    return LuaString.valueOf(data);
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("write_binary", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue dataArg, LuaValue appendArg) {
                FileOutputStream fos = null;
                try {
                    byte[] bytes = extractRawBytes(dataArg);
                    File f = new File(pathArg.checkjstring());
                    if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
                    fos = new FileOutputStream(f, appendArg.toboolean());
                    fos.write(bytes);
                    fos.flush();
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                }
            }
        });

        globals.set("read_bytes", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                try {
                    byte[] data = readFileBytes(new File(pathArg.checkjstring()));
                    LuaTable tbl = new LuaTable();
                    for (int i = 0; i < data.length; i++) {
                        tbl.set(i + 1, LuaValue.valueOf(data[i] & 0xFF));
                    }
                    return tbl;
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("write_bytes", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue tblArg, LuaValue appendArg) {
                FileOutputStream fos = null;
                try {
                    byte[] bytes = extractRawBytes(tblArg);
                    File f = new File(pathArg.checkjstring());
                    if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
                    fos = new FileOutputStream(f, appendArg.toboolean());
                    fos.write(bytes);
                    fos.flush();
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                }
            }
        });

        globals.set("base64_encode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue dataArg) {
                return LuaValue.valueOf(encodeBase64(extractRawBytes(dataArg)));
            }
        });

        globals.set("base64_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue b64Arg) {
                byte[] decoded = SqlHttpServer.decodeBase64Flexible(b64Arg.checkjstring());
                return LuaString.valueOf(decoded);
            }
        });

        globals.set("hex_encode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue dataArg) {
                byte[] b = extractRawBytes(dataArg);
                StringBuilder sb = new StringBuilder(b.length * 2);
                for (int i = 0; i < b.length; i++) sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
                return LuaValue.valueOf(sb.toString());
            }
        });

        globals.set("hex_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue hexArg) {
                String s = hexArg.checkjstring().trim();
                byte[] out = new byte[s.length() / 2];
                for (int i = 0; i + 1 < s.length(); i += 2) {
                    out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
                }
                return LuaString.valueOf(out);
            }
        });

        globals.set("hash_data", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue algoArg, LuaValue dataArg) {
                try {
                    MessageDigest md = MessageDigest.getInstance(algoArg.checkjstring());
                    byte[] dig = md.digest(extractRawBytes(dataArg));
                    return LuaValue.valueOf(bytesToHex(dig));
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("hash_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue algoArg, LuaValue pathArg) {
                InputStream is = null;
                try {
                    MessageDigest md = MessageDigest.getInstance(algoArg.checkjstring());
                    is = new BufferedInputStream(new FileInputStream(new File(pathArg.checkjstring())));
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
                    return LuaValue.valueOf(bytesToHex(md.digest()));
                } catch (Exception e) {
                    return LuaValue.NIL;
                } finally {
                    try { if (is != null) is.close(); } catch (Exception ignored) {}
                }
            }
        });

        // =====================================================================
        // 8. FILESYSTEM: COPY, MOVE, RENAME, METADATA, READ/WRITE
        // =====================================================================
        globals.set("files_dir", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(appCtx.getFilesDir().getAbsolutePath()); }
        });

        globals.set("cache_dir", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(appCtx.getCacheDir().getAbsolutePath()); }
        });

        globals.set("sdcard_dir", new ZeroArgFunction() {
            @Override public LuaValue call() {
                File ext = Environment.getExternalStorageDirectory();
                return LuaValue.valueOf(ext != null ? ext.getAbsolutePath() : "/sdcard");
            }
        });

        globals.set("read_file", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                try { return LuaValue.valueOf(new String(readFileBytes(new File(pathArg.checkjstring())), "UTF-8")); }
                catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("write_file", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue dataArg, LuaValue appendArg) {
                FileOutputStream fos = null;
                try {
                    File f = new File(pathArg.checkjstring());
                    if (f.getParentFile() != null && !f.getParentFile().exists()) f.getParentFile().mkdirs();
                    fos = new FileOutputStream(f, appendArg.toboolean());
                    fos.write(dataArg.tojstring().getBytes("UTF-8"));
                    fos.flush();
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    return LuaValue.FALSE;
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                }
            }
        });

        globals.set("copy_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue srcArg, LuaValue dstArg) {
                try {
                    copyRecursive(new File(srcArg.checkjstring()), new File(dstArg.checkjstring()));
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("move_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue srcArg, LuaValue dstArg) {
                try {
                    File src = new File(srcArg.checkjstring());
                    File dst = new File(dstArg.checkjstring());
                    if (!src.exists()) return LuaValue.FALSE;
                    if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();
                    if (src.renameTo(dst)) return LuaValue.TRUE;
                    copyRecursive(src, dst);
                    deleteRecursive(src);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("rename_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue srcArg, LuaValue newArg) {
                try {
                    File src = new File(srcArg.checkjstring());
                    String target = newArg.checkjstring();
                    File dst = (target.indexOf('/') >= 0 || target.indexOf('\\') >= 0)
                            ? new File(target)
                            : new File(src.getParentFile(), target);
                    return LuaValue.valueOf(src.renameTo(dst));
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("file_info", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                File f = new File(pathArg.checkjstring());
                LuaTable tbl = new LuaTable();
                tbl.set("exists", LuaValue.valueOf(f.exists()));
                tbl.set("is_dir", LuaValue.valueOf(f.isDirectory()));
                tbl.set("is_file", LuaValue.valueOf(f.isFile()));
                tbl.set("size", LuaValue.valueOf(f.length()));
                tbl.set("last_modified", LuaValue.valueOf(f.lastModified()));
                tbl.set("can_read", LuaValue.valueOf(f.canRead()));
                tbl.set("can_write", LuaValue.valueOf(f.canWrite()));
                tbl.set("name", LuaValue.valueOf(f.getName()));
                tbl.set("path", LuaValue.valueOf(f.getAbsolutePath()));
                return tbl;
            }
        });

        globals.set("file_exists", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(new File(p.checkjstring()).exists()); }
        });

        globals.set("mkdirs", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) {
                File f = new File(p.checkjstring());
                return LuaValue.valueOf(f.exists() || f.mkdirs());
            }
        });

        globals.set("delete_file", new OneArgFunction() {
            @Override public LuaValue call(LuaValue p) { return LuaValue.valueOf(deleteRecursive(new File(p.checkjstring()))); }
        });

        globals.set("list_dir", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg) {
                File dir = new File(pathArg.checkjstring());
                File[] files = dir.listFiles();
                LuaTable tbl = new LuaTable();
                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        LuaTable entry = new LuaTable();
                        entry.set("name", LuaValue.valueOf(files[i].getName()));
                        entry.set("path", LuaValue.valueOf(files[i].getAbsolutePath()));
                        entry.set("is_dir", LuaValue.valueOf(files[i].isDirectory()));
                        entry.set("size", LuaValue.valueOf(files[i].length()));
                        entry.set("last_modified", LuaValue.valueOf(files[i].lastModified()));
                        tbl.set(i + 1, entry);
                    }
                }
                return tbl;
            }
        });

        // =====================================================================
        // 9. HARDWARE SENSORS & LOCATION
        // =====================================================================
        globals.set("list_sensors", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    SensorManager sm = (SensorManager) appCtx.getSystemService(Context.SENSOR_SERVICE);
                    if (sm != null) {
                        List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
                        for (int i = 0; i < list.size(); i++) {
                            Sensor s = list.get(i);
                            LuaTable row = new LuaTable();
                            row.set("name", LuaValue.valueOf(s.getName()));
                            row.set("type", LuaValue.valueOf(s.getType()));
                            row.set("vendor", LuaValue.valueOf(s.getVendor()));
                            row.set("max_range", LuaValue.valueOf(s.getMaximumRange()));
                            row.set("resolution", LuaValue.valueOf(s.getResolution()));
                            tbl.set(i + 1, row);
                        }
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        globals.set("read_sensor", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue typeArg, LuaValue timeoutArg) {
                int sensorType = resolveSensorType(typeArg);
                long timeoutMs = timeoutArg.isint() ? timeoutArg.tolong() : 2000L;
                return readSingleSensorSample(appCtx, sensorType, timeoutMs);
            }
        });

        globals.set("get_location", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    LocationManager lm = (LocationManager) appCtx.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null) {
                        Location loc = null;
                        try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
                        if (loc == null) {
                            try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
                        }
                        if (loc != null) {
                            tbl.set("lat", LuaValue.valueOf(loc.getLatitude()));
                            tbl.set("lon", LuaValue.valueOf(loc.getLongitude()));
                            tbl.set("alt", LuaValue.valueOf(loc.getAltitude()));
                            tbl.set("accuracy", LuaValue.valueOf(loc.getAccuracy()));
                            tbl.set("speed", LuaValue.valueOf(loc.getSpeed()));
                            tbl.set("provider", LuaValue.valueOf(String.valueOf(loc.getProvider())));
                            tbl.set("time", LuaValue.valueOf(loc.getTime()));
                        }
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        // =====================================================================
        // 10. SQLDROID, SYNC, JSON, SHELL, INTENTS, CLIPBOARD & TELEMETRY
        // =====================================================================
        globals.set("sql_query", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String dbPath = args.arg(1).checkjstring();
                String sql = args.arg(2).checkjstring();
                try {
                    SqlHttpServer.SqlQueryResult res = SqlHttpServer.executeSqlWithSqlDroid(dbPath, sql);
                    if (!res.ok) return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(res.error) });
                    LuaTable rowsTbl = new LuaTable();
                    if (res.rows != null && res.columns != null) {
                        for (int r = 0; r < res.rows.size(); r++) {
                            List<String> row = res.rows.get(r);
                            LuaTable rowTbl = new LuaTable();
                            for (int c = 0; c < res.columns.size(); c++) {
                                String val = c < row.size() ? row.get(c) : null;
                                LuaValue lv = (val == null) ? LuaValue.NIL : LuaValue.valueOf(val);
                                rowTbl.set(c + 1, lv);
                                rowTbl.set(res.columns.get(c), lv);
                            }
                            rowsTbl.set(r + 1, rowTbl);
                        }
                    } else {
                        rowsTbl.set("rows_affected", LuaValue.valueOf(res.rowsAffected));
                    }
                    return LuaValue.varargsOf(new LuaValue[] { rowsTbl, LuaValue.NIL });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] { LuaValue.NIL, LuaValue.valueOf(String.valueOf(e.getMessage())) });
                }
            }
        });

        globals.set("run_sync", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue jobArg) {
                int j = jobArg.toint();
                if (j >= 1 && j <= 5) j = j - 1;
                return LuaValue.valueOf(SyncRunner.runJobAndScript(appCtx, resolveActivity(activity), j, false, logger));
            }
        });

        globals.set("json_encode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue val) {
                try {
                    Object obj = luaToJavaJson(val);
                    return LuaValue.valueOf(obj != null ? obj.toString() : "null");
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        globals.set("json_decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue strArg) {
                try {
                    String s = strArg.checkjstring().trim();
                    if (s.startsWith("[")) return javaJsonToLua(new JSONArray(s));
                    return javaJsonToLua(new JSONObject(s));
                } catch (Exception e) { return LuaValue.NIL; }
            }
        });

        // shell(cmdOrScriptPath) -> exitCode, stdout, stderr (uses /system/bin/sh + full PATH)
        globals.set("shell", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String cmd = args.arg(1).checkjstring();
                try {
                    SyncRunner.ShellResult sr = SyncRunner.runAndroidShell(appCtx, cmd);
                    return LuaValue.varargsOf(new LuaValue[] {
                            LuaValue.valueOf(sr.exitCode), LuaValue.valueOf(sr.stdout), LuaValue.valueOf(sr.stderr)
                    });
                } catch (Exception e) {
                    return LuaValue.varargsOf(new LuaValue[] {
                            LuaValue.valueOf(-1), LuaValue.valueOf(""), LuaValue.valueOf(String.valueOf(e.getMessage()))
                    });
                }
            }
        });

        globals.set("open_url", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue urlArg) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(urlArg.checkjstring()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(i);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("open_file", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue pathArg, LuaValue mimeArg) {
                try {
                    File f = new File(pathArg.checkjstring());
                    String mime = mimeArg.isnil() ? "*/*" : mimeArg.tojstring();
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.fromFile(f), mime);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(i);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("launch_app", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue pkgArg) {
                try {
                    Intent i = appCtx.getPackageManager().getLaunchIntentForPackage(pkgArg.checkjstring());
                    if (i == null) return LuaValue.FALSE;
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(i);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("share_text", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg, LuaValue textArg) {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, titleArg.isnil() ? "Asyncer" : titleArg.tojstring());
                    send.putExtra(Intent.EXTRA_TEXT, textArg.tojstring());
                    Intent chooser = Intent.createChooser(send, titleArg.isnil() ? "Share" : titleArg.tojstring());
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(chooser);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("send_broadcast", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue actionArg, LuaValue extrasArg) {
                try {
                    Intent intent = new Intent(actionArg.checkjstring());
                    if (extrasArg.istable()) applyLuaTableToIntent(intent, extrasArg.checktable());
                    appCtx.sendBroadcast(intent);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("start_activity", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue pkgArg, LuaValue clsArg, LuaValue extrasArg) {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(pkgArg.checkjstring(), clsArg.checkjstring()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (extrasArg.istable()) applyLuaTableToIntent(intent, extrasArg.checktable());
                    appCtx.startActivity(intent);
                    return LuaValue.TRUE;
                } catch (Exception e) { return LuaValue.FALSE; }
            }
        });

        globals.set("set_clipboard", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue textArg) {
                final String text = textArg.tojstring();
                final AtomicBoolean ok = new AtomicBoolean(false);
                final CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        try {
                            Object cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (Build.VERSION.SDK_INT >= 11) {
                                Class<?> clipDataCls = Class.forName("android.content.ClipData");
                                Object clip = clipDataCls.getMethod("newPlainText", CharSequence.class, CharSequence.class)
                                        .invoke(null, "Asyncer", text);
                                cm.getClass().getMethod("setPrimaryClip", clipDataCls).invoke(cm, clip);
                            } else {
                                cm.getClass().getMethod("setText", CharSequence.class).invoke(cm, text);
                            }
                            ok.set(true);
                        } catch (Throwable ignored) {
                        } finally { latch.countDown(); }
                    }
                });
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.valueOf(ok.get());
            }
        });

        globals.set("get_clipboard", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                final AtomicReference<String> ref = new AtomicReference<String>("");
                final CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        try {
                            Object cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (Build.VERSION.SDK_INT >= 11) {
                                Object clip = cm.getClass().getMethod("getPrimaryClip").invoke(cm);
                                if (clip != null) {
                                    Object item = clip.getClass().getMethod("getItemAt", int.class).invoke(clip, Integer.valueOf(0));
                                    Object cs = item.getClass().getMethod("coerceToText", Context.class).invoke(item, appCtx);
                                    if (cs != null) ref.set(cs.toString());
                                }
                            } else {
                                Object cs = cm.getClass().getMethod("getText").invoke(cm);
                                if (cs != null) ref.set(cs.toString());
                            }
                        } catch (Throwable ignored) {
                        } finally { latch.countDown(); }
                    }
                });
                try { latch.await(); } catch (InterruptedException ignored) {}
                return LuaValue.valueOf(ref.get());
            }
        });

        globals.set("battery_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    Intent bat = appCtx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (bat != null) {
                        int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        int temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                        int pct = (scale > 0) ? (level * 100 / scale) : level;
                        boolean charging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
                        tbl.set("level", LuaValue.valueOf(pct));
                        tbl.set("charging", LuaValue.valueOf(charging));
                        tbl.set("temp", LuaValue.valueOf(temp / 10.0));
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        globals.set("network_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                try {
                    ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                    tbl.set("connected", LuaValue.valueOf(ni != null && ni.isConnected()));
                    tbl.set("type", LuaValue.valueOf(ni != null ? ni.getTypeName() : "NONE"));
                    WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
                    if (wi != null) {
                        int ip = wi.getIpAddress();
                        String ipStr = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
                        tbl.set("ip", LuaValue.valueOf(ipStr));
                        tbl.set("ssid", LuaValue.valueOf(String.valueOf(wi.getSSID())));
                    }
                } catch (Throwable ignored) {}
                return tbl;
            }
        });

        globals.set("device_info", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LuaTable tbl = new LuaTable();
                tbl.set("model", LuaValue.valueOf(Build.MODEL));
                tbl.set("manufacturer", LuaValue.valueOf(Build.MANUFACTURER));
                tbl.set("sdk", LuaValue.valueOf(Build.VERSION.SDK_INT));
                tbl.set("release", LuaValue.valueOf(Build.VERSION.RELEASE));
                return tbl;
            }
        });

        globals.set("prefs_get", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg, LuaValue defArg) {
                SharedPreferences sp = appCtx.getSharedPreferences("asyncer_lua_prefs", Context.MODE_PRIVATE);
                return LuaValue.valueOf(sp.getString(keyArg.checkjstring(), defArg.isnil() ? "" : defArg.tojstring()));
            }
        });

        globals.set("prefs_set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue keyArg, LuaValue valArg) {
                SharedPreferences sp = appCtx.getSharedPreferences("asyncer_lua_prefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = sp.edit();
                if (valArg.isnil()) ed.remove(keyArg.checkjstring());
                else ed.putString(keyArg.checkjstring(), valArg.tojstring());
                ed.commit();
                return LuaValue.TRUE;
            }
        });

        LuaValue chunk = globals.loadfile(luaPath);
        LuaValue res = chunk.call();
        return res != null && !res.isnil() ? res.tojstring() : "OK";
    }

    // =========================================================================
    // INTERNAL HELPER IMPLEMENTATIONS
    // =========================================================================

    private static SmbClient.ConnectionProfile buildSmbProfile(Varargs args) {
        SmbClient.ConnectionProfile p = new SmbClient.ConnectionProfile();
        p.host = args.arg(1).checkjstring();
        p.server = p.host;
        p.port = args.arg(2).isint() ? args.arg(2).toint() : 445;
        p.username = args.arg(3).isnil() ? "guest" : args.arg(3).tojstring();
        p.user = p.username;
        p.password = args.arg(4).isnil() ? "" : args.arg(4).tojstring();
        p.pass = p.password;
        p.share = args.arg(5).checkjstring();
        return p;
    }

    private static boolean speakTextCompat(final Context ctx, Handler mainHandler, final String text, final float rate, final float pitch) {
        try {
            if (ttsEngine == null || !ttsReady) {
                final CountDownLatch initLatch = new CountDownLatch(1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        ttsEngine = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                            public void onInit(int status) {
                                ttsReady = (status == TextToSpeech.SUCCESS);
                                initLatch.countDown();
                            }
                        });
                    }
                });
                initLatch.await(4, TimeUnit.SECONDS);
            }
            if (ttsEngine != null && ttsReady) {
                ttsEngine.setSpeechRate(rate);
                ttsEngine.setPitch(pitch);
                int r = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                return r == TextToSpeech.SUCCESS;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static synchronized boolean playSoundCompat(Context ctx, String src, boolean loop) {
        stopSoundCompat();
        try {
            mediaPlayer = new MediaPlayer();
            if (src.startsWith("http://") || src.startsWith("https://") || src.startsWith("content://")) {
                mediaPlayer.setDataSource(ctx, Uri.parse(src));
            } else {
                mediaPlayer.setDataSource(src);
            }
            mediaPlayer.setLooping(loop);
            mediaPlayer.prepare();
            mediaPlayer.start();
            return true;
        } catch (Throwable t) {
            stopSoundCompat();
            return false;
        }
    }

    private static synchronized void stopSoundCompat() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            }
        } catch (Throwable ignored) {}
        mediaPlayer = null;
    }

    private static int resolveSensorType(LuaValue typeArg) {
        if (typeArg.isint()) return typeArg.toint();
        String s = typeArg.tojstring().trim().toLowerCase();
        if ("accelerometer".equals(s) || "accel".equals(s)) return 1;
        if ("magnetic".equals(s) || "compass".equals(s)) return 2;
        if ("orientation".equals(s)) return 3;
        if ("gyroscope".equals(s) || "gyro".equals(s)) return 4;
        if ("light".equals(s)) return 5;
        if ("pressure".equals(s) || "barometer".equals(s)) return 6;
        if ("proximity".equals(s)) return 8;
        if ("gravity".equals(s)) return 9;
        if ("linear_acceleration".equals(s)) return 10;
        if ("rotation_vector".equals(s)) return 11;
        if ("humidity".equals(s)) return 12;
        if ("temperature".equals(s) || "ambient_temperature".equals(s)) return 13;
        return 1;
    }

    private static LuaValue readSingleSensorSample(Context ctx, int sensorType, long timeoutMs) {
        final LuaTable tbl = new LuaTable();
        try {
            final SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return LuaValue.NIL;
            final Sensor sensor = sm.getDefaultSensor(sensorType);
            if (sensor == null) return LuaValue.NIL;

            tbl.set("name", LuaValue.valueOf(sensor.getName()));
            tbl.set("type", LuaValue.valueOf(sensor.getType()));

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<float[]> valsRef = new AtomicReference<float[]>(null);
            final AtomicInteger accRef = new AtomicInteger(0);

            SensorEventListener listener = new SensorEventListener() {
                public void onSensorChanged(SensorEvent event) {
                    if (event != null && event.values != null && valsRef.get() == null) {
                        float[] copy = new float[event.values.length];
                        System.arraycopy(event.values, 0, copy, 0, event.values.length);
                        valsRef.set(copy);
                        accRef.set(event.accuracy);
                        latch.countDown();
                    }
                }
                public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };

            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI);
            try {
                latch.await(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS);
            } finally {
                sm.unregisterListener(listener);
            }

            float[] v = valsRef.get();
            if (v == null) return LuaValue.NIL;

            LuaTable valTbl = new LuaTable();
            for (int i = 0; i < v.length; i++) valTbl.set(i + 1, LuaValue.valueOf(v[i]));
            tbl.set("values", valTbl);
            if (v.length >= 1) tbl.set("x", LuaValue.valueOf(v[0]));
            if (v.length >= 2) tbl.set("y", LuaValue.valueOf(v[1]));
            if (v.length >= 3) tbl.set("z", LuaValue.valueOf(v[2]));
            tbl.set("accuracy", LuaValue.valueOf(accRef.get()));
            return tbl;
        } catch (Throwable t) {
            return LuaValue.NIL;
        }
    }

    private static byte[] extractRawBytes(LuaValue val) {
        if (val.istable()) {
            LuaTable tbl = val.checktable();
            int len = tbl.length();
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) out[i] = (byte) (tbl.get(i + 1).toint() & 0xFF);
            return out;
        }
        LuaString ls = val.checkstring();
        byte[] out = new byte[ls.rawlen()];
        ls.copyInto(0, out, 0, out.length);
        return out;
    }

    private static byte[] readFileBytes(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(f.length(), 1024 * 1024));
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally {
            fis.close();
        }
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (!src.exists()) throw new Exception("Source does not exist");
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    copyRecursive(children[i], new File(dst, children[i].getName()));
                }
            }
        } else {
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();
            InputStream in = new BufferedInputStream(new FileInputStream(src));
            OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                in.close();
                out.close();
            }
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) deleteRecursive(children[i]);
            }
        }
        return f.delete();
    }

    private static String encodeBase64(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b = (data[i] & 0xFF) << 16;
            if (i + 1 < data.length) b |= (data[i + 1] & 0xFF) << 8;
            if (i + 2 < data.length) b |= (data[i + 2] & 0xFF);
            sb.append(alphabet[(b >> 18) & 0x3F]);
            sb.append(alphabet[(b >> 12) & 0x3F]);
            sb.append((i + 1 < data.length) ? alphabet[(b >> 6) & 0x3F] : '=');
            sb.append((i + 2 < data.length) ? alphabet[b & 0x3F] : '=');
        }
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xFF));
        return sb.toString();
    }

    private static Object luaToJavaJson(LuaValue v) throws Exception {
        if (v == null || v.isnil()) return JSONObject.NULL;
        if (v.isboolean()) return Boolean.valueOf(v.toboolean());
        if (v.isint()) return Integer.valueOf(v.toint());
        if (v.isnumber()) return Double.valueOf(v.todouble());
        if (v.isstring()) return v.tojstring();
        if (v.istable()) {
            LuaTable tbl = v.checktable();
            int len = tbl.length();
            if (len > 0) {
                JSONArray arr = new JSONArray();
                for (int i = 1; i <= len; i++) arr.put(luaToJavaJson(tbl.get(i)));
                return arr;
            }
            JSONObject obj = new JSONObject();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = tbl.next(k);
                if ((k = n.arg1()).isnil()) break;
                obj.put(k.tojstring(), luaToJavaJson(n.arg(2)));
            }
            return obj;
        }
        return v.tojstring();
    }

    private static LuaValue javaJsonToLua(Object obj) throws Exception {
        if (obj == null || obj == JSONObject.NULL) return LuaValue.NIL;
        if (obj instanceof Boolean) return LuaValue.valueOf(((Boolean) obj).booleanValue());
        if (obj instanceof Number) return LuaValue.valueOf(((Number) obj).doubleValue());
        if (obj instanceof String) return LuaValue.valueOf((String) obj);
        if (obj instanceof JSONArray) {
            JSONArray arr = (JSONArray) obj;
            LuaTable tbl = new LuaTable();
            for (int i = 0; i < arr.length(); i++) tbl.set(i + 1, javaJsonToLua(arr.get(i)));
            return tbl;
        }
        if (obj instanceof JSONObject) {
            JSONObject jo = (JSONObject) obj;
            LuaTable tbl = new LuaTable();
            Iterator<?> it = jo.keys();
            while (it.hasNext()) {
                String k = String.valueOf(it.next());
                tbl.set(k, javaJsonToLua(jo.get(k)));
            }
            return tbl;
        }
        return LuaValue.valueOf(String.valueOf(obj));
    }

    private static void configureTrustAllSsl(HttpsURLConnection https) throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new SecureRandom());
        https.setSSLSocketFactory(sc.getSocketFactory());
        https.setHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) { return true; }
        });
    }

    private static Varargs doHttpRequest(String method, String urlStr, String body, String contentType,
                                         LuaTable headers, boolean trustAll) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (trustAll && conn instanceof HttpsURLConnection) {
                configureTrustAllSsl((HttpsURLConnection) conn);
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                LuaValue k = LuaValue.NIL;
                while (true) {
                    Varargs n = headers.next(k);
                    if ((k = n.arg1()).isnil()) break;
                    conn.setRequestProperty(k.tojstring(), n.arg(2).tojstring());
                }
            }
            if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                conn.setDoOutput(true);
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
                byte[] outBytes = body.getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(outBytes);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = is != null ? readStreamUtf8(is) : "";
            return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(code), LuaValue.valueOf(resp) });
        } catch (Exception e) {
            return LuaValue.varargsOf(new LuaValue[] { LuaValue.valueOf(-1), LuaValue.valueOf(String.valueOf(e.getMessage())) });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void applyLuaTableToIntent(Intent intent, LuaTable tbl) {
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = tbl.next(k);
            if ((k = n.arg1()).isnil()) break;
            String key = k.tojstring();
            LuaValue v = n.arg(2);
            if (v.isboolean()) intent.putExtra(key, v.toboolean());
            else if (v.isint()) intent.putExtra(key, v.toint());
            else intent.putExtra(key, v.tojstring());
        }
    }

    private static String readStreamUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        is.close();
        return baos.toString("UTF-8");
    }

    private static void postNotificationCompat(Context ctx, int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent intent = new Intent(ctx, MainActivity.class);
            int flags = (Build.VERSION.SDK_INT >= 23)
                    ? (PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000)
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(ctx, id, intent, flags);
            Notification notif = null;

            if (Build.VERSION.SDK_INT >= 26) {
                String chanId = "asyncer_lua_channel";
                Class<?> chanCls = Class.forName("android.app.NotificationChannel");
                Object chan = chanCls.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(chanId, "Asyncer Script Notifications", Integer.valueOf(3));
                NotificationManager.class.getMethod("createNotificationChannel", chanCls).invoke(nm, chan);

                Class<?> bldCls = Class.forName("android.app.Notification$Builder");
                Object bld = bldCls.getConstructor(Context.class, String.class).newInstance(ctx, chanId);
                bldCls.getMethod("setContentTitle", CharSequence.class).invoke(bld, title);
                bldCls.getMethod("setContentText", CharSequence.class).invoke(bld, text);
                bldCls.getMethod("setContentIntent", PendingIntent.class).invoke(bld, pi);
                bldCls.getMethod("setSmallIcon", int.class).invoke(bld, Integer.valueOf(android.R.drawable.ic_popup_sync));
                notif = (Notification) bldCls.getMethod("build").invoke(bld);
            } else if (Build.VERSION.SDK_INT >= 11) {
                Class<?> bldCls = Class.forName("android.app.Notification$Builder");
                Object bld = bldCls.getConstructor(Context.class).newInstance(ctx);
                bldCls.getMethod("setContentTitle", CharSequence.class).invoke(bld, title);
                bldCls.getMethod("setContentText", CharSequence.class).invoke(bld, text);
                bldCls.getMethod("setContentIntent", PendingIntent.class).invoke(bld, pi);
                bldCls.getMethod("setSmallIcon", int.class).invoke(bld, Integer.valueOf(android.R.drawable.ic_popup_sync));
                notif = (Notification) bldCls.getMethod(Build.VERSION.SDK_INT >= 16 ? "build" : "getNotification").invoke(bld);
            } else {
                notif = new Notification(android.R.drawable.ic_popup_sync, title, System.currentTimeMillis());
                Method setLatest = Notification.class.getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                setLatest.invoke(notif, ctx, title, text, pi);
            }
            if (notif != null) nm.notify(id, notif);
        } catch (Throwable ignored) {}
    }
    }
