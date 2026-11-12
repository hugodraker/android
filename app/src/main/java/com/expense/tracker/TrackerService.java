package com.expense.tracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class TrackerService extends Service {
    private static final String ACTION_POLL = "com.expense.tracker.ACTION_POLL";

    private LocationManager locationManager;
    private AlarmManager alarmManager;
    private PendingIntent pollPendingIntent;
    private PowerManager.WakeLock wakeLock;
    private Handler timeoutHandler;

    private SQLiteDatabase db;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private boolean isWaitingForFix = false;
    private SharedPreferences prefs;

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_MOVING = 1;
    private static final int STATE_STATIONARY = 2;

    private int currentState = STATE_UNKNOWN;
    private long stateStartTime = 0;
    private Location lastValidLocation = null;
    private Location stationaryCandidate = null;
    private long stationaryCandidateSince = 0;
    private Location anchorLocation = null;

    // Accumulated trip distance in meters
    private double accumulatedTripMeters = 0.0;
    private String currentPlaceName = "SITE";
    private String originName = "SITE";

    private static long blockConnectionsUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        timeoutHandler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelTracker:PollWakeLock");
            wakeLock.setReferenceCounted(false);
        }

        // Restore last known valid fix from preferences so coordinates are never 0,0
        double savedLat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
        double savedLon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));
        if (savedLat != 0.0 || savedLon != 0.0) {
            lastValidLocation = new Location("saved");
            lastValidLocation.setLatitude(savedLat);
            lastValidLocation.setLongitude(savedLon);
        }

        db = new SQLiteOpenHelper(this, "travel_events.db", null, 2) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, lat REAL, lon REAL, start_time INTEGER, end_time INTEGER, duration_hours REAL, dist_km REAL, dist_mi REAL)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion < 2) {
                    try { db.execSQL("ALTER TABLE events ADD COLUMN dist_km REAL DEFAULT 0"); } catch (Exception ignored) {}
                    try { db.execSQL("ALTER TABLE events ADD COLUMN dist_mi REAL DEFAULT 0"); } catch (Exception ignored) {}
                }
            }
        }.getWritableDatabase();

        startForegroundCompatible();
        scheduleAlarmPolling();
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_POLL.equals(intent.getAction())) {
            triggerSingleLocationPoll();
        }
        return 1; // START_STICKY
    }

    // Android 1.6 (API 4) to Android 14 (API 34) compatible foreground notification
    @SuppressWarnings("deprecation")
    private void startForegroundCompatible() {
        Intent intent = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);
        Notification notification;

        if (Build.VERSION.SDK_INT >= 26) {
            String channelId = "travel_tracker_channel";
            NotificationChannel channel = new NotificationChannel(channelId, "Travel Tracking", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
            notification = new Notification.Builder(this, channelId)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Recording location at preset intervals.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 16) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Recording location at preset intervals.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Recording location at preset intervals.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
        } else {
            // Android 1.6 - 2.3 (API 4 - 10)
            notification = new Notification(android.R.drawable.ic_menu_mylocation, "Travel Expense Tracker Active", System.currentTimeMillis());
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            try {
                Method m = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                m.invoke(notification, this, "Travel Expense Tracker Active", "Recording location at preset intervals.", pi);
            } catch (Exception ignored) {}
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= 5) {
            startForeground(1, notification);
        } else {
            // Android 1.6 (API 4) fallback
            try {
                Method setFg = Service.class.getMethod("setForeground", boolean.class);
                setFg.invoke(this, true);
            } catch (Exception ignored) {}
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(1, notification);
        }
    }

    private void scheduleAlarmPolling() {
        long interval = prefs.getLong("interval", 60000L);
        if (interval < 10000L) interval = 10000L;

        Intent pollIntent = new Intent(this, TrackerService.class);
        pollIntent.setAction(ACTION_POLL);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT;
        pollPendingIntent = PendingIntent.getService(this, 0, pollIntent, piFlags);

        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000L, interval, pollPendingIntent);
    }

    private void triggerSingleLocationPoll() {
        if (!isWorkingTime() || isWaitingForFix) return;

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(15000L);
        }

        isWaitingForFix = true;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, oneShotListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, oneShotListener);
            }
        } catch (SecurityException ignored) {}

        // Shut off GPS radio after 12 seconds if no fresh satellite fix arrives, and reuse last known location
        timeoutHandler.postDelayed(fixTimeoutRunnable, 12000L);
    }

    private final Runnable fixTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isWaitingForFix) return;
            stopListeningForFix();
            Location fallback = getBestFallbackLocation();
            if (fallback != null) {
                processLocation(fallback);
            }
            releaseWakeLock();
        }
    };

    private final LocationListener oneShotListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || !isValidCoordinate(location.getLatitude(), location.getLongitude())) {
                return; // Ignore invalid 0,0 fixes
            }
            timeoutHandler.removeCallbacks(fixTimeoutRunnable);
            stopListeningForFix();
            saveValidLocation(location);
            processLocation(location);
            releaseWakeLock();
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private void stopListeningForFix() {
        isWaitingForFix = false;
        try {
            locationManager.removeUpdates(oneShotListener);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return Math.abs(lat) > 0.0001 || Math.abs(lon) > 0.0001;
    }

    private void saveValidLocation(Location loc) {
        lastValidLocation = new Location(loc);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putLong("last_valid_lat", Double.doubleToRawLongBits(loc.getLatitude()));
        ed.putLong("last_valid_lon", Double.doubleToRawLongBits(loc.getLongitude()));
        ed.commit();
    }

    private Location getBestFallbackLocation() {
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null && isValidCoordinate(gps.getLatitude(), gps.getLongitude())) {
                saveValidLocation(gps);
                return gps;
            }
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (net != null && isValidCoordinate(net.getLatitude(), net.getLongitude())) {
                saveValidLocation(net);
                return net;
            }
        } catch (SecurityException ignored) {}
        return lastValidLocation;
    }

    private static class PlaceMatch {
        String name;
        boolean omitted;
        PlaceMatch(String n, boolean o) { name = n; omitted = o; }
    }

    private PlaceMatch getPlace(Location loc) {
        String saved = prefs.getString("special_locations", "");
        if (saved.length() == 0) return new PlaceMatch("SITE", false);

        float threshold = prefs.getFloat("distance", 50.0f);
        String[] entries = saved.split(";");
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|");
            if (parts.length == 3) {
                try {
                    String[] coords = parts[1].split(",");
                    Location sLoc = new Location("");
                    sLoc.setLatitude(Double.parseDouble(coords[0].trim()));
                    sLoc.setLongitude(Double.parseDouble(coords[1].trim()));
                    if (loc.distanceTo(sLoc) <= threshold) {
                        return new PlaceMatch(parts[0], Boolean.parseBoolean(parts[2]));
                    }
                } catch (Exception ignored) {}
            }
        }
        return new PlaceMatch("SITE", false);
    }

    private void processLocation(Location location) {
        long now = System.currentTimeMillis();
        float distThreshold = prefs.getFloat("distance", 50.0f);
        long stayRequiredMs = prefs.getInt("stay_mins", 5) * 60000L;
        if (stayRequiredMs < 60000L) stayRequiredMs = 60000L;

        if (currentState == STATE_UNKNOWN) {
            currentState = STATE_STATIONARY;
            stateStartTime = now;
            anchorLocation = location;
            stationaryCandidate = location;
            stationaryCandidateSince = now;
            currentPlaceName = getPlace(location).name;
            originName = currentPlaceName;
            accumulatedTripMeters = 0.0;
            return;
        }

        if (currentState == STATE_STATIONARY) {
            float moveDist = location.distanceTo(anchorLocation);
            if (moveDist > distThreshold) {
                PlaceMatch origin = getPlace(anchorLocation);
                // Record completed stay duration (0 distance for a stay)
                if (!origin.omitted) {
                    logEvent("STAY AT " + currentPlaceName, anchorLocation.getLatitude(), anchorLocation.getLongitude(), stateStartTime, now, 0.0);
                }

                // Transition to moving & start accumulating trip distance
                currentState = STATE_MOVING;
                stateStartTime = now;
                originName = currentPlaceName;
                accumulatedTripMeters = moveDist;
                stationaryCandidate = location;
                stationaryCandidateSince = now;
            }
        } else if (currentState == STATE_MOVING) {
            float stepDist = location.distanceTo(stationaryCandidate);
            if (stepDist <= distThreshold) {
                // Staying within radius of candidate destination
                if ((now - stationaryCandidateSince) >= stayRequiredMs) {
                    PlaceMatch dest = getPlace(stationaryCandidate);
                    String tripName = buildTripName(originName, dest.name);

                    // Record trip and accumulated distance now that user has stayed for preset interval
                    logEvent(tripName, stationaryCandidate.getLatitude(), stationaryCandidate.getLongitude(), stateStartTime, stationaryCandidateSince, accumulatedTripMeters);

                    currentState = STATE_STATIONARY;
                    stateStartTime = stationaryCandidateSince;
                    anchorLocation = stationaryCandidate;
                    currentPlaceName = dest.name;
                    accumulatedTripMeters = 0.0;
                }
            } else {
                // Still moving: accumulate distance and reset stationary candidate timer
                accumulatedTripMeters += stepDist;
                stationaryCandidate = location;
                stationaryCandidateSince = now;
            }
        }
    }

    private String buildTripName(String orig, String dest) {
        if (orig.equals("SITE") && dest.equals("SITE")) return "SITE TO SITE";
        if (orig.equals("SITE")) return "TO " + dest;
        if (dest.equals("SITE")) return "FROM " + orig;
        return orig + " TO " + dest;
    }

    private void logEvent(String type, double lat, double lon, long start, long end, double distanceMeters) {
        if ((end - start) < 30000L) return;
        double hours = (end - start) / 3600000.0;
        double distKm = distanceMeters / 1000.0;
        double distMi = distanceMeters / 1609.344;
        db.execSQL("INSERT INTO events (type, lat, lon, start_time, end_time, duration_hours, dist_km, dist_mi) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{type, lat, lon, start, end, hours, distKm, distMi});
    }

    private boolean isWorkingTime() {
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        return hour >= prefs.getInt("start_hour", 0) && hour < prefs.getInt("end_hour", 24);
    }

    private void startHttpServer() {
        isRunning = true;
        final int port = prefs.getInt("http_port", 8080);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    while (isRunning) {
                        Socket socket = serverSocket.accept();
                        handleRequest(socket);
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void handleRequest(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            String requestLine = in.readLine();

            if (requestLine == null) return;
            if (System.currentTimeMillis() < blockConnectionsUntil) {
                socket.close();
                return;
            }

            String[] reqParts = requestLine.split(" ");
            if (reqParts.length < 2 || !reqParts[1].contains("?")) {
                socket.close();
                return;
            }

            String query = reqParts[1].substring(reqParts[1].indexOf("?") + 1);
            String[] params = query.split("&");
            if (params.length < 3) {
                socket.close();
                return;
            }

            String expectedAuth = prefs.getString("http_user", "admin") + "=" + prefs.getString("http_pass", "password");
            if (!params[0].equals(expectedAuth)) {
                blockConnectionsUntil = System.currentTimeMillis() + (3 * 60 * 1000);
                out.write("HTTP/1.1 403 Forbidden\r\n\r\nBad Credentials. Locked for 3 mins.".getBytes("US-ASCII"));
                out.flush();
                socket.close();
                return;
            }

            SimpleDateFormat parseFormat = new SimpleDateFormat("yyyyMMddHHmm", Locale.US);
            long qStart, qEnd;
            try {
                qStart = parseFormat.parse(params[1]).getTime();
                qEnd = parseFormat.parse(params[2]).getTime();
            } catch (Exception e) {
                socket.close();
                return;
            }

            StringBuilder csv = new StringBuilder("Event Type,Latitude,Longitude,Start Time,End Time,Duration (Hours),Distance (km),Distance (mi)\n");
            Cursor c = db.rawQuery("SELECT type, lat, lon, start_time, end_time, duration_hours, dist_km, dist_mi FROM events WHERE start_time >= ? AND start_time <= ? ORDER BY start_time DESC",
                    new String[]{String.valueOf(qStart), String.valueOf(qEnd)});

            SimpleDateFormat outFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            while (c.moveToNext()) {
                csv.append(c.getString(0)).append(",")
                   .append(String.format(Locale.US, "%.6f", c.getDouble(1))).append(",")
                   .append(String.format(Locale.US, "%.6f", c.getDouble(2))).append(",")
                   .append(outFormat.format(new Date(c.getLong(3)))).append(",")
                   .append(outFormat.format(new Date(c.getLong(4)))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(5))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(6))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(7))).append("\n");
            }
            c.close();

            String data = csv.toString();
            String filename = params[1].substring(0, 8) + "-tracker.csv";

            String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/csv\r\n" +
                            "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n" +
                            "Content-Length: " + data.length() + "\r\n\r\n";

            out.write(header.getBytes("US-ASCII"));
            out.write(data.getBytes("US-ASCII"));
            out.flush();
            socket.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (alarmManager != null && pollPendingIntent != null) {
            alarmManager.cancel(pollPendingIntent);
        }
        timeoutHandler.removeCallbacks(fixTimeoutRunnable);
        stopListeningForFix();
        releaseWakeLock();

        if (currentState != STATE_UNKNOWN && lastValidLocation != null) {
            logEvent("TRACKING STOPPED AT " + currentPlaceName, lastValidLocation.getLatitude(), lastValidLocation.getLongitude(), stateStartTime, System.currentTimeMillis(), accumulatedTripMeters);
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (db != null) db.close();
        if (Build.VERSION.SDK_INT < 5) {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(1);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
