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
    public static final String ACTION_POLL = "com.expense.tracker.ACTION_POLL";
    public static final String ACTION_RELOAD_SETTINGS = "com.expense.tracker.ACTION_RELOAD_SETTINGS";

    private LocationManager locationManager;
    private AlarmManager alarmManager;
    private PendingIntent pollPendingIntent;
    private PowerManager.WakeLock persistentWakeLock;
    private Handler mainHandler;

    private SQLiteDatabase db;
    private ServerSocket serverSocket;
    private int currentServerPort = -1;
    private boolean isRunning = false;
    private boolean isWaitingForFix = false;
    private SharedPreferences prefs;

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
        final double lat;
        final double lon;
        final boolean isKnown;
        final boolean omitted;

        PlaceMatch(String name, double lat, double lon, boolean isKnown, boolean omitted) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.isKnown = isKnown;
            this.omitted = omitted;
        }
    }

    // Confirmed current site where user is anchored
    private GpsPoint confirmedAnchor = null;
    private PlaceMatch confirmedPlace = null;
    private long confirmedStayStart = 0;
    private long confirmedStayLastSeen = 0;

    // In-memory trajectory points collected while moving to a new site
    private final List<GpsPoint> memoryPoints = new ArrayList<GpsPoint>();
    private GpsPoint clusterCenter = null;
    private long clusterStartTime = 0;

    // Temporary best network fix while waiting for satellite lock
    private Location pendingNetworkFix = null;
    private GpsPoint lastValidPoint = null;
    private static long blockConnectionsUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            persistentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelTracker:continuousLock");
            persistentWakeLock.setReferenceCounted(false);
            persistentWakeLock.acquire();
        }

        double savedLat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
        double savedLon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));
        if (isValidCoordinate(savedLat, savedLon)) {
            lastValidPoint = new GpsPoint(savedLat, savedLon, System.currentTimeMillis(), 20.0f);
        }

        // Restore persisted origin anchor so service restarts never lose the current site
        double anchorLat = Double.longBitsToDouble(prefs.getLong("anchor_lat", 0));
        double anchorLon = Double.longBitsToDouble(prefs.getLong("anchor_lon", 0));
        if (isValidCoordinate(anchorLat, anchorLon)) {
            long aStart = prefs.getLong("anchor_start", System.currentTimeMillis());
            long aLast = prefs.getLong("anchor_last_seen", System.currentTimeMillis());
            confirmedAnchor = new GpsPoint(anchorLat, anchorLon, aLast, 15.0f);
            confirmedPlace = getPlace(anchorLat, anchorLon);
            confirmedStayStart = aStart;
            confirmedStayLastSeen = aLast;
        }

        db = new SQLiteOpenHelper(this, "travel_events.db", null, 3) {
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
                if (oldVersion < 3) {
                    try {
                        db.execSQL("DELETE FROM events WHERE type LIKE 'STAY AT%' OR type LIKE 'TRACKING STOPPED%' OR dist_km <= 0.0001");
                    } catch (Exception ignored) {}
                }
            }
        }.getWritableDatabase();

        // Clean any leftover STAY AT or 0-distance rows
        try {
            db.execSQL("DELETE FROM events WHERE type LIKE 'STAY AT%' OR type LIKE 'TRACKING STOPPED%' OR dist_km <= 0.0001");
        } catch (Exception ignored) {}

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
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_POLL.equals(action)) {
                long interval = Math.max(10000L, prefs.getLong("interval", 60000L));
                scheduleNextPoll(interval);
                triggerSingleLocationPoll();
            } else if (ACTION_RELOAD_SETTINGS.equals(action)) {
                if (confirmedAnchor != null) {
                    confirmedPlace = getPlace(confirmedAnchor.lat, confirmedAnchor.lon);
                }
                long interval = Math.max(10000L, prefs.getLong("interval", 60000L));
                scheduleNextPoll(interval);
                int newPort = prefs.getInt("http_port", 8080);
                if (newPort != currentServerPort) {
                    startHttpServer();
                }
            }
        }
        return 1; // START_STICKY
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
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
                    .setContentText("Tracking site-to-site trips in memory.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 16) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Tracking site-to-site trips in memory.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Tracking site-to-site trips in memory.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
        } else {
            notification = new Notification(android.R.drawable.ic_menu_mylocation, "Travel Expense Tracker Active", System.currentTimeMillis());
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            try {
                Method m = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                m.invoke(notification, this, "Travel Expense Tracker Active", "Tracking site-to-site trips in memory.", pi);
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
        pendingNetworkFix = null;

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, gpsListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, netListener);
            }
        } catch (SecurityException ignored) {}

        long interval = Math.max(15000L, prefs.getLong("interval", 60000L));
        long timeout = Math.min(20000L, interval - 2000L);
        mainHandler.postDelayed(fixTimeoutRunnable, timeout);
    }

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || !isValidCoordinate(location.getLatitude(), location.getLongitude())) {
                return;
            }
            if (location.hasAccuracy() && location.getAccuracy() > 100.0f) {
                return;
            }
            mainHandler.removeCallbacks(fixTimeoutRunnable);
            stopListeningForFix();

            float acc = location.hasAccuracy() ? location.getAccuracy() : 15.0f;
            GpsPoint pt = new GpsPoint(location.getLatitude(), location.getLongitude(), System.currentTimeMillis(), acc);
            saveValidPoint(pt);
            onPointSampled(pt);
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    // Network listener only caches a high-accuracy Wi-Fi fix in case GPS satellites time out indoors
    private final LocationListener netListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || !isValidCoordinate(location.getLatitude(), location.getLongitude())) {
                return;
            }
            // Reject coarse cell-tower jumps (> 65m accuracy)
            if (location.hasAccuracy() && location.getAccuracy() > 65.0f) {
                return;
            }
            if (pendingNetworkFix == null || (location.hasAccuracy() && location.getAccuracy() < pendingNetworkFix.getAccuracy())) {
                pendingNetworkFix = location;
            }
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private final Runnable fixTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isWaitingForFix) return;
            stopListeningForFix();

            long now = System.currentTimeMillis();
            if (pendingNetworkFix != null) {
                float acc = pendingNetworkFix.hasAccuracy() ? pendingNetworkFix.getAccuracy() : 40.0f;
                GpsPoint pt = new GpsPoint(pendingNetworkFix.getLatitude(), pendingNetworkFix.getLongitude(), now, acc);
                saveValidPoint(pt);
                onPointSampled(pt);
                return;
            }

            // If stationary at confirmedAnchor (indoors with no satellite signal), update stay timestamp using anchor
            if (confirmedAnchor != null && memoryPoints.isEmpty()) {
                confirmedStayLastSeen = now;
                persistAnchorState();
            } else if (confirmedAnchor == null) {
                GpsPoint fallback = getInitialFallbackPoint();
                if (fallback != null) {
                    onPointSampled(fallback);
                }
            }
        }
    };

    private void stopListeningForFix() {
        isWaitingForFix = false;
        try { locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
        try { locationManager.removeUpdates(netListener); } catch (Exception ignored) {}
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

    private void persistAnchorState() {
        if (confirmedAnchor == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putLong("anchor_lat", Double.doubleToRawLongBits(confirmedAnchor.lat));
        ed.putLong("anchor_lon", Double.doubleToRawLongBits(confirmedAnchor.lon));
        ed.putLong("anchor_start", confirmedStayStart);
        ed.putLong("anchor_last_seen", confirmedStayLastSeen);
        ed.commit();
    }

    private GpsPoint getInitialFallbackPoint() {
        long now = System.currentTimeMillis();
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gps != null && isValidCoordinate(gps.getLatitude(), gps.getLongitude())) {
                GpsPoint p = new GpsPoint(gps.getLatitude(), gps.getLongitude(), now, 25.0f);
                saveValidPoint(p);
                return p;
            }
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (net != null && isValidCoordinate(net.getLatitude(), net.getLongitude())) {
                GpsPoint p = new GpsPoint(net.getLatitude(), net.getLongitude(), now, 50.0f);
                saveValidPoint(p);
                return p;
            }
        } catch (SecurityException ignored) {}
        return lastValidPoint;
    }

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
        if (saved.length() > 0) {
            float threshold = Math.max(50.0f, prefs.getFloat("distance", 75.0f));
            String[] entries = saved.split(";");
            PlaceMatch bestMatch = null;
            double bestDist = Double.MAX_VALUE;

            for (int i = 0; i < entries.length; i++) {
                String[] parts = entries[i].split("\\|");
                if (parts.length == 3) {
                    try {
                        String[] coords = parts[1].split(",");
                        double sLat = Double.parseDouble(coords[0].trim());
                        double sLon = Double.parseDouble(coords[1].trim());
                        double d = distanceMeters(lat, lon, sLat, sLon);
                        if (d <= threshold && d < bestDist) {
                            bestDist = d;
                            bestMatch = new PlaceMatch(parts[0].trim().toUpperCase(Locale.US), sLat, sLon, true, Boolean.parseBoolean(parts[2].trim()));
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (bestMatch != null) return bestMatch;
        }
        return new PlaceMatch("SITE", lat, lon, false, false);
    }

    // In-Memory Trajectory Engine: holds points in memory and only writes when a move to a new site is confirmed
    private void onPointSampled(GpsPoint pt) {
        float siteRadius = Math.max(50.0f, prefs.getFloat("distance", 75.0f));
        long stayRequiredMs = Math.max(60000L, prefs.getInt("stay_mins", 5) * 60000L);
        long pollIntervalMs = Math.max(10000L, prefs.getLong("interval", 60000L));

        // 1. Lock initial site if none exists yet
        if (confirmedAnchor == null) {
            PlaceMatch match = getPlace(pt.lat, pt.lon);
            confirmedAnchor = new GpsPoint(match.lat, match.lon, pt.time, pt.accuracy);
            confirmedPlace = match;
            confirmedStayStart = pt.time;
            confirmedStayLastSeen = pt.time;
            persistAnchorState();
            return;
        }

        // 2. Check if user is still at confirmedAnchor while no trip is active
        if (memoryPoints.isEmpty()) {
            double distFromAnchor = distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, pt.lat, pt.lon);
            PlaceMatch currentMatch = getPlace(pt.lat, pt.lon);
            boolean sameKnownSite = confirmedPlace.isKnown && currentMatch.isKnown && confirmedPlace.name.equalsIgnoreCase(currentMatch.name);

            if (distFromAnchor <= siteRadius || sameKnownSite) {
                confirmedStayLastSeen = pt.time;
                persistAnchorState();
                return;
            }

            // User moved outside current site: begin buffering points in memory
            long departTime = confirmedStayLastSeen;
            if ((pt.time - departTime) > (pollIntervalMs * 3L)) {
                departTime = pt.time - pollIntervalMs;
            }
            memoryPoints.add(new GpsPoint(confirmedAnchor.lat, confirmedAnchor.lon, departTime, confirmedAnchor.accuracy));
            memoryPoints.add(pt);
            clusterCenter = pt;
            clusterStartTime = pt.time;
            return;
        }

        // 3. User is moving: keep points in memory
        memoryPoints.add(pt);

        double distFromCluster = distanceMeters(clusterCenter.lat, clusterCenter.lon, pt.lat, pt.lon);
        if (distFromCluster <= siteRadius) {
            // Stationary at candidate site; wait until stayRequiredMs is reached
            if ((pt.time - clusterStartTime) >= stayRequiredMs) {
                PlaceMatch newPlace = getPlace(clusterCenter.lat, clusterCenter.lon);
                double destLat = newPlace.isKnown ? newPlace.lat : clusterCenter.lat;
                double destLon = newPlace.isKnown ? newPlace.lon : clusterCenter.lon;

                double linearDist = distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, destLat, destLon);
                boolean sameKnownPlace = confirmedPlace.isKnown && newPlace.isKnown && confirmedPlace.name.equalsIgnoreCase(newPlace.name);
                boolean returnedToSameSite = linearDist <= siteRadius;

                if (sameKnownPlace || returnedToSameSite) {
                    // Returned to same site without staying at a new site: discard buffer
                    confirmedStayLastSeen = pt.time;
                    persistAnchorState();
                    memoryPoints.clear();
                    clusterCenter = null;
                    return;
                }

                // Confirmed move to a new site: calculate rolling distance with linear fallback
                double rollingDist = calculateRollingTripMeters(destLat, destLon, siteRadius);
                double finalDistMeters = Math.max(rollingDist, linearDist);

                long departTime = memoryPoints.get(0).time;
                long arriveTime = Math.max(departTime + 1000L, clusterStartTime);
                String eventType = buildTripName(confirmedPlace.name, newPlace.name);

                logEvent(eventType, destLat, destLon, departTime, arriveTime, finalDistMeters);

                // Advance confirmed anchor to the new site and clear memory buffer
                confirmedAnchor = new GpsPoint(destLat, destLon, clusterStartTime, clusterCenter.accuracy);
                confirmedPlace = newPlace;
                confirmedStayStart = clusterStartTime;
                confirmedStayLastSeen = pt.time;
                persistAnchorState();
                memoryPoints.clear();
                clusterCenter = null;
            }
        } else {
            // Still moving: advance cluster center to current point
            clusterCenter = pt;
            clusterStartTime = pt.time;
        }
    }

    private double calculateRollingTripMeters(double destLat, double destLon, float siteRadius) {
        if (memoryPoints.size() < 2) {
            return distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, destLat, destLon);
        }

        double rolling = 0.0;
        GpsPoint prev = memoryPoints.get(0);

        for (int i = 1; i < memoryPoints.size(); i++) {
            GpsPoint curr = memoryPoints.get(i);
            // Stop accumulating once inside the destination stay cluster after arrival time
            if (curr.time > clusterStartTime && distanceMeters(destLat, destLon, curr.lat, curr.lon) <= siteRadius) {
                continue;
            }
            double seg = distanceMeters(prev.lat, prev.lon, curr.lat, curr.lon);
            if (seg >= 10.0) {
                rolling += seg;
                prev = curr;
            }
        }

        // Add remaining linear distance from the last moving point to the destination coordinate
        double tail = distanceMeters(prev.lat, prev.lon, destLat, destLon);
        if (tail >= 10.0) {
            rolling += tail;
        }
        return rolling;
    }

    // Always formats as "<ORIGIN> TO <DEST>" where unknown sites are "SITE"
    private String buildTripName(String orig, String dest) {
        String o = (orig == null || orig.length() == 0) ? "SITE" : orig.toUpperCase(Locale.US);
        String d = (dest == null || dest.length() == 0) ? "SITE" : dest.toUpperCase(Locale.US);
        return o + " TO " + d;
    }

    private void logEvent(String type, double lat, double lon, long start, long end, double distanceMeters) {
        if (end <= start || distanceMeters <= 1.0) return;
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
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}

        isRunning = true;
        final int port = prefs.getInt("http_port", 8080);
        currentServerPort = port;

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

            boolean viewInline = false;
            for (int i = 3; i < params.length; i++) {
                if (params[i].equalsIgnoreCase("view=1")) {
                    viewInline = true;
                    break;
                }
            }

            boolean includeCoords = prefs.getBoolean("output_locations", false);

            StringBuilder csv = new StringBuilder();
            if (includeCoords) {
                csv.append("Event Type,Latitude,Longitude,Start Time,End Time,Duration (Hours),Distance (mi),Distance (km)\n");
            } else {
                csv.append("Event Type,Start Time,End Time,Duration (Hours),Distance (mi),Distance (km)\n");
            }

            Cursor c = db.rawQuery("SELECT type, lat, lon, start_time, end_time, duration_hours, dist_mi, dist_km FROM events WHERE start_time >= ? AND start_time <= ? ORDER BY start_time DESC",
                    new String[]{String.valueOf(qStart), String.valueOf(qEnd)});

            SimpleDateFormat outFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            while (c.moveToNext()) {
                csv.append(c.getString(0)).append(",");
                if (includeCoords) {
                    csv.append(String.format(Locale.US, "%.6f", c.getDouble(1))).append(",")
                       .append(String.format(Locale.US, "%.6f", c.getDouble(2))).append(",");
                }
                csv.append(outFormat.format(new Date(c.getLong(3)))).append(",")
                   .append(outFormat.format(new Date(c.getLong(4)))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(5))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(6))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(7))).append("\n");
            }
            c.close();

            String data = csv.toString();
            String filename = params[1].substring(0, 8) + "-tracker.csv";

            String header;
            if (viewInline) {
                header = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: text/plain; charset=utf-8\r\n" +
                         "Content-Length: " + data.length() + "\r\n\r\n";
            } else {
                header = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: text/csv\r\n" +
                         "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n" +
                         "Content-Length: " + data.length() + "\r\n\r\n";
            }

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
        persistAnchorState();

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
