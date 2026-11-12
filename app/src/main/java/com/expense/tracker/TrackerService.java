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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TrackerService extends Service {
    private static final String ACTION_POLL = "com.expense.tracker.ACTION_POLL";

    private LocationManager locationManager;
    private AlarmManager alarmManager;
    private PendingIntent pollPendingIntent;
    private PowerManager.WakeLock persistentWakeLock;
    private Handler mainHandler;

    private SQLiteDatabase db;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private boolean isWaitingForFix = false;
    private SharedPreferences prefs;

    // In-memory GPS breadcrumb point
    private static class GpsPoint {
        final double lat;
        final double lon;
        final long time;
        final float accuracy;

        GpsPoint(double lat, double lon, long time, float accuracy) {
            this.lat = lat;
            this.lon = lon;
            this.time = time;
            this.accuracy = accuracy;
        }
    }

    private static class PlaceMatch {
        final String name;
        final boolean omitted;
        PlaceMatch(String n, boolean o) { name = n; omitted = o; }
    }

    // Confirmed Stay Anchor State
    private GpsPoint confirmedAnchor = null;
    private PlaceMatch confirmedPlace = null;
    private long confirmedStayStart = 0;
    private long confirmedStayLastSeen = 0;

    // In-Memory Candidate Trip Buffer (only committed once destination stay is verified)
    private final List<GpsPoint> tripBuffer = new ArrayList<GpsPoint>();
    private GpsPoint clusterCenter = null;
    private long clusterStartTime = 0;

    private GpsPoint lastValidPoint = null;
    private static long blockConnectionsUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        // Hold a continuous PARTIAL_WAKE_LOCK while service is running so CPU never sleeps
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            persistentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelTracker: continuousLock");
            persistentWakeLock.setReferenceCounted(false);
            persistentWakeLock.acquire();
        }

        double savedLat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
        double savedLon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));
        if (isValidCoordinate(savedLat, savedLon)) {
            lastValidPoint = new GpsPoint(savedLat, savedLon, System.currentTimeMillis(), 20.0f);
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
        scheduleNextPoll(500L);
        startPeriodicHandlerFallback();
        startHttpServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (persistentWakeLock != null && !persistentWakeLock.isHeld()) {
            try { persistentWakeLock.acquire(); } catch (Exception ignored) {}
        }
        if (intent != null && ACTION_POLL.equals(intent.getAction())) {
            long interval = Math.max(10000L, prefs.getLong("interval", 60000L));
            scheduleNextPoll(interval);
            triggerSingleLocationPoll();
        }
        return 1; // START_STICKY
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Immediately reschedule if swiped away from recents
        scheduleNextPoll(2000L);
        super.onTaskRemoved(rootIntent);
    }

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
                    .setContentText("Recording verified site stays and trips.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 16) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Recording verified site stays and trips.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Recording verified site stays and trips.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
        } else {
            notification = new Notification(android.R.drawable.ic_menu_mylocation, "Travel Expense Tracker Active", System.currentTimeMillis());
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            try {
                Method m = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                m.invoke(notification, this, "Travel Expense Tracker Active", "Recording verified site stays and trips.", pi);
            } catch (Exception ignored) {}
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else if (Build.VERSION.SDK_INT >= 5) {
            startForeground(1, notification);
        } else {
            try {
                Method setFg = Service.class.getMethod("setForeground", boolean.class);
                setFg.invoke(this, true);
            } catch (Exception ignored) {}
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(1, notification);
        }
    }

    // Schedules exact wake-up alarms even in Android 6+ Doze mode
    private void scheduleNextPoll(long delayMs) {
        if (alarmManager == null) return;
        Intent pollIntent = new Intent(this, TrackerService.class);
        pollIntent.setAction(ACTION_POLL);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT;
        pollPendingIntent = PendingIntent.getService(this, 0, pollIntent, piFlags);

        long triggerAt = System.currentTimeMillis() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pollPendingIntent);
            } else if (Build.VERSION.SDK_INT >= 19) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pollPendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pollPendingIntent);
            }
        } catch (Exception e) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pollPendingIntent);
        }
    }

    // Secondary in-process heartbeat tied to the continuous WakeLock
    private void startPeriodicHandlerFallback() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                triggerSingleLocationPoll();
                long interval = Math.max(10000L, prefs.getLong("interval", 60000L));
                mainHandler.postDelayed(this, interval);
            }
        }, Math.max(10000L, prefs.getLong("interval", 60000L)));
    }

    private void triggerSingleLocationPoll() {
        if (!isWorkingTime() || isWaitingForFix) return;
        isWaitingForFix = true;

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, oneShotListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, oneShotListener);
            }
        } catch (SecurityException ignored) {}

        mainHandler.postDelayed(fixTimeoutRunnable, 12000L);
    }

    private final Runnable fixTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isWaitingForFix) return;
            stopListeningForFix();
            GpsPoint fallback = getBestFallbackPoint();
            if (fallback != null) {
                onPointSampled(fallback);
            }
        }
    };

    private final LocationListener oneShotListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || !isValidCoordinate(location.getLatitude(), location.getLongitude())) {
                return;
            }
            // Ignore wildly inaccurate cell-tower jumps (> 200m accuracy) if we already have a fix
            if (location.hasAccuracy() && location.getAccuracy() > 200.0f && lastValidPoint != null) {
                return;
            }
            mainHandler.removeCallbacks(fixTimeoutRunnable);
            stopListeningForFix();

            float acc = location.hasAccuracy() ? location.getAccuracy() : 25.0f;
            GpsPoint pt = new GpsPoint(location.getLatitude(), location.getLongitude(), System.currentTimeMillis(), acc);
            saveValidPoint(pt);
            onPointSampled(pt);
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

    private boolean isValidCoordinate(double lat, double lon) {
        return Math.abs(lat) > 0.0001 || Math.abs(lon) > 0.0001;
    }

    private void saveValidPoint(GpsPoint pt) {
        lastValidPoint = pt;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putLong("last_valid_lat", Double.doubleToRawLongBits(pt.lat));
        ed.putLong("last_valid_lon", Double.doubleToRawLongBits(pt.lon));
        ed.commit();
    }

    private GpsPoint getBestFallbackPoint() {
        long now = System.currentTimeMillis();
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null && isValidCoordinate(gps.getLatitude(), gps.getLongitude())) {
                GpsPoint p = new GpsPoint(gps.getLatitude(), gps.getLongitude(), now, gps.hasAccuracy() ? gps.getAccuracy() : 30.0f);
                saveValidPoint(p);
                return p;
            }
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (net != null && isValidCoordinate(net.getLatitude(), net.getLongitude())) {
                GpsPoint p = new GpsPoint(net.getLatitude(), net.getLongitude(), now, net.hasAccuracy() ? net.getAccuracy() : 50.0f);
                saveValidPoint(p);
                return p;
            }
        } catch (SecurityException ignored) {}

        if (lastValidPoint != null) {
            return new GpsPoint(lastValidPoint.lat, lastValidPoint.lon, now, lastValidPoint.accuracy);
        }
        return null;
    }

    // Accurate Haversine distance in meters between two lat/lon pairs
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return R * c;
    }

    private PlaceMatch getPlace(double lat, double lon) {
        String saved = prefs.getString("special_locations", "");
        if (saved.length() == 0) return new PlaceMatch("SITE", false);

        float threshold = Math.max(50.0f, prefs.getFloat("distance", 75.0f));
        String[] entries = saved.split(";");
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|");
            if (parts.length == 3) {
                try {
                    String[] coords = parts[1].split(",");
                    double sLat = Double.parseDouble(coords[0].trim());
                    double sLon = Double.parseDouble(coords[1].trim());
                    if (distanceMeters(lat, lon, sLat, sLon) <= threshold) {
                        return new PlaceMatch(parts[0].trim(), Boolean.parseBoolean(parts[2].trim()));
                    }
                } catch (Exception ignored) {}
            }
        }
        return new PlaceMatch("SITE", false);
    }

    // Core In-Memory Trajectory & Stay Verification Engine
    private void onPointSampled(GpsPoint pt) {
        float siteRadius = Math.max(50.0f, prefs.getFloat("distance", 75.0f));
        long stayRequiredMs = Math.max(60000L, prefs.getInt("stay_mins", 5) * 60000L);

        // 1. First point ever recorded: lock initial anchor
        if (confirmedAnchor == null) {
            confirmedAnchor = pt;
            confirmedPlace = getPlace(pt.lat, pt.lon);
            confirmedStayStart = pt.time;
            confirmedStayLastSeen = pt.time;
            return;
        }

        // 2. If we have no active trip buffer, check if we are still at confirmedAnchor
        if (tripBuffer.isEmpty()) {
            double distFromAnchor = distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, pt.lat, pt.lon);
            PlaceMatch currentMatch = getPlace(pt.lat, pt.lon);

            // Same named location OR within radius -> still at the same site
            boolean sameNamedSite = !confirmedPlace.name.equals("SITE") && confirmedPlace.name.equalsIgnoreCase(currentMatch.name);
            if (distFromAnchor <= siteRadius || sameNamedSite) {
                confirmedStayLastSeen = pt.time;
                return;
            }

            // Moved outside site radius: start buffering points in memory
            tripBuffer.add(confirmedAnchor);
            tripBuffer.add(pt);
            clusterCenter = pt;
            clusterStartTime = pt.time;
            return;
        }

        // 3. Currently buffering a potential trip in memory
        GpsPoint prevPt = tripBuffer.get(tripBuffer.size() - 1);
        double stepDist = distanceMeters(prevPt.lat, prevPt.lon, pt.lat, pt.lon);
        // Only append breadcrumb if movement exceeds GPS jitter floor (15m)
        if (stepDist >= 15.0) {
            tripBuffer.add(pt);
        }

        double distFromCluster = distanceMeters(clusterCenter.lat, clusterCenter.lon, pt.lat, pt.lon);
        if (distFromCluster <= siteRadius) {
            // User is stationary around clusterCenter; check if stay duration is met
            if ((pt.time - clusterStartTime) >= stayRequiredMs) {
                PlaceMatch newPlace = getPlace(clusterCenter.lat, clusterCenter.lon);
                double netDisplacement = distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, clusterCenter.lat, clusterCenter.lon);

                boolean sameNamedPlace = !confirmedPlace.name.equals("SITE") && confirmedPlace.name.equalsIgnoreCase(newPlace.name);
                booleanreturnedToSameSpot = netDisplacement <= siteRadius;

                if (sameNamedPlace || returnedToSameSpot) {
                    // False alarm / GPS drift / returned to same site without staying elsewhere:
                    // Discard buffer so we NEVER log "OFFICE TO OFFICE" or "HOME TO HOME"
                    confirmedStayLastSeen = pt.time;
                    tripBuffer.clear();
                    clusterCenter = null;
                    return;
                }

                // VALIDATED TRIP TO A NEW LOCATION:
                // Step A: Commit the finished stay at the previous anchor (if not omitted)
                long departureTime = tripBuffer.size() > 1 ? tripBuffer.get(1).time : confirmedStayLastSeen;
                if (departureTime < confirmedStayLastSeen) departureTime = confirmedStayLastSeen;

                if (!confirmedPlace.omitted && (departureTime - confirmedStayStart) >= 60000L) {
                    logEvent("STAY AT " + confirmedPlace.name, confirmedAnchor.lat, confirmedAnchor.lon, confirmedStayStart, departureTime, 0.0);
                }

                // Step B: Calculate verified trip distance across buffered points (prior to cluster arrival)
                double pathMeters = calculateBufferedTripMeters(clusterCenter, siteRadius);
                if (pathMeters < netDisplacement) {
                    pathMeters = netDisplacement; // Never allow distance < straight-line displacement
                }

                String tripType = buildTripName(confirmedPlace.name, newPlace.name);
                long arrivalTime = Math.max(departureTime + 1000L, clusterStartTime);
                logEvent(tripType, clusterCenter.lat, clusterCenter.lon, departureTime, arrivalTime, pathMeters);

                // Step C: Lock in the new confirmed stay anchor
                confirmedAnchor = clusterCenter;
                confirmedPlace = newPlace;
                confirmedStayStart = clusterStartTime;
                confirmedStayLastSeen = pt.time;
                tripBuffer.clear();
                clusterCenter = null;
            }
        } else {
            // Still moving: shift candidate cluster center to current point
            clusterCenter = pt;
            clusterStartTime = pt.time;
        }
    }

    private double calculateBufferedTripMeters(GpsPoint destCenter, float siteRadius) {
        double total = 0.0;
        if (tripBuffer.size() < 2) return 0.0;

        GpsPoint prev = tripBuffer.get(0);
        for (int i = 1; i < tripBuffer.size(); i++) {
            GpsPoint curr = tripBuffer.get(i);
            double step = distanceMeters(prev.lat, prev.lon, curr.lat, curr.lon);
            // Filter out stationary jitter inside the destination cluster after arrival
            if (curr.time > clusterStartTime && distanceMeters(destCenter.lat, destCenter.lon, curr.lat, curr.lon) <= siteRadius) {
                continue;
            }
            if (step >= 15.0) {
                total += step;
                prev = curr;
            }
        }
        return total;
    }

    private String buildTripName(String orig, String dest) {
        if (orig.equals("SITE") && dest.equals("SITE")) return "SITE TO SITE";
        if (orig.equals("SITE")) return "TO " + dest;
        if (dest.equals("SITE")) return "FROM " + orig;
        return orig + " TO " + dest;
    }

    private void logEvent(String type, double lat, double lon, long start, long end, double distanceMeters) {
        if (end <= start) return;
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

            // Distance (km) moved to the last column as requested
            StringBuilder csv = new StringBuilder("Event Type,Latitude,Longitude,Start Time,End Time,Duration (Hours),Distance (mi),Distance (km)\n");
            Cursor c = db.rawQuery("SELECT type, lat, lon, start_time, end_time, duration_hours, dist_mi, dist_km FROM events WHERE start_time >= ? AND start_time <= ? ORDER BY start_time DESC",
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
        mainHandler.removeCallbacksAndMessages(null);
        stopListeningForFix();

        // Flush any confirmed non-omitted stay before stopping
        if (confirmedAnchor != null && confirmedPlace != null && !confirmedPlace.omitted) {
            long end = Math.max(confirmedStayLastSeen, System.currentTimeMillis());
            if ((end - confirmedStayStart) >= 60000L) {
                logEvent("STAY AT " + confirmedPlace.name, confirmedAnchor.lat, confirmedAnchor.lon, confirmedStayStart, end, 0.0);
            }
        }

        if (persistentWakeLock != null && persistentWakeLock.isHeld()) {
            try { persistentWakeLock.release(); } catch (Exception ignored) {}
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
