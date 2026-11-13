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
import java.net.InetAddress;
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
    public static final String ACTION_ERASE_DATA = "com.expense.tracker.ACTION_ERASE_DATA";
    public static final String ACTION_LOCATION_UPDATED = "com.expense.tracker.ACTION_LOCATION_UPDATED";

    private LocationManager locationManager;
    private AlarmManager alarmManager;
    private PendingIntent pollPendingIntent;
    private PowerManager.WakeLock persistentWakeLock;
    private Handler mainHandler;

    private SQLiteDatabase db;
    private ServerSocket serverSocket;
    private int currentServerPort = -1;
    private boolean isRunning = false;
    private boolean hasAcquiredLiveFix = false;
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

    // Confirmed starting site
    private GpsPoint confirmedAnchor = null;
    private PlaceMatch confirmedPlace = null;
    private long confirmedStayStart = 0;
    private long confirmedStayLastSeen = 0;

    // In-memory trajectory points buffered while moving to a new site
    private final List<GpsPoint> memoryPoints = new ArrayList<GpsPoint>();
    private GpsPoint clusterCenter = null;
    private long clusterStartTime = 0;

    // Latest hardware GNSS location received from native LocationManager
    private GpsPoint latestHardwarePoint = null;
    private GpsPoint lastValidPoint = null;
    private long lastProcessedIntervalTime = 0;
    private static long blockConnectionsUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        isRunning = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            persistentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelTracker:continuousLock");
            persistentWakeLock.setReferenceCounted(false);
            persistentWakeLock.acquire();
        }

        double savedLat = Double.longBitsToDouble(prefs.getLong("last_valid_lat", 0));
        double savedLon = Double.longBitsToDouble(prefs.getLong("last_valid_lon", 0));
        if (isValidCoordinate(savedLat, savedLon)) {
            lastValidPoint = new GpsPoint(savedLat, savedLon, System.currentTimeMillis(), 15.0f);
        }

        double anchorLat = Double.longBitsToDouble(prefs.getLong("anchor_lat", 0));
        double anchorLon = Double.longBitsToDouble(prefs.getLong("anchor_lon", 0));
        if (isValidCoordinate(anchorLat, anchorLon)) {
            long aStart = prefs.getLong("anchor_start", System.currentTimeMillis());
            long aLast = prefs.getLong("anchor_last_seen", System.currentTimeMillis());
            confirmedAnchor = new GpsPoint(anchorLat, anchorLon, aLast, 10.0f);
            confirmedPlace = getPlace(anchorLat, anchorLon);
            confirmedStayStart = aStart;
            confirmedStayLastSeen = aLast;
        }

        db = new SQLiteOpenHelper(this, "travel_events.db", null, 3) {
            @Override
            public void onConfigure(SQLiteDatabase db) {
                super.onConfigure(db);
                if (Build.VERSION.SDK_INT >= 16) {
                    try { db.disableWriteAheadLogging(); } catch (Exception ignored) {}
                }
            }

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

        try {
            Cursor c = db.rawQuery("PRAGMA journal_mode=DELETE;", null);
            if (c != null) c.close();
        } catch (Exception ignored) {}

        startForegroundCompatible();
        startNativeGnssProviders();
        scheduleNextPoll(1000L);
        startIntervalTicker();
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
                long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
                scheduleNextPoll(interval);
                processIntervalTick();
            } else if (ACTION_RELOAD_SETTINGS.equals(action)) {
                if (confirmedAnchor != null) {
                    confirmedPlace = getPlace(confirmedAnchor.lat, confirmedAnchor.lon);
                }
                startNativeGnssProviders();
                long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
                scheduleNextPoll(interval);
                int newPort = prefs.getInt("http_port", 8080);
                if (newPort != currentServerPort) {
                    startHttpServer();
                }
            } else if (ACTION_ERASE_DATA.equals(action)) {
                try { db.execSQL("DELETE FROM events"); } catch (Exception ignored) {}
                memoryPoints.clear();
                confirmedAnchor = null;
                confirmedPlace = null;
                clusterCenter = null;
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
                    .setContentText("Standalone GPS tracking active.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 16) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Standalone GPS tracking active.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Standalone GPS tracking active.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
        } else {
            notification = new Notification(android.R.drawable.ic_menu_mylocation, "Travel Expense Tracker Active", System.currentTimeMillis());
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            try {
                Method m = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                m.invoke(notification, this, "Travel Expense Tracker Active", "Standalone GPS tracking active.", pi);
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

    /**
     * Organic Maps-style native GNSS provider registration:
     * Uses pure Android LocationManager without Google Play Services.
     * While acquiring initial satellite lock, keeps GPS hardware powered at 1000ms (0m)
     * so cold-start satellite ephemeris download is never interrupted prematurely.
     */
    private void startNativeGnssProviders() {
        if (locationManager == null) return;
        try {
            locationManager.removeUpdates(nativeGnssListener);
        } catch (Exception ignored) {}

        long userInterval = Math.max(1000L, prefs.getLong("interval", 60000L));
        // Keep GPS chip active at 1s until first satellite fix locks; afterwards poll at min(userInterval, 5000ms)
        long gnssRate = hasAcquiredLiveFix ? Math.min(userInterval, 5000L) : 1000L;

        try {
            List<String> allProviders = locationManager.getAllProviders();
            if (allProviders != null) {
                if (allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, gnssRate, 0.0f, nativeGnssListener, Looper.getMainLooper());
                }
                if (allProviders.contains("passive")) {
                    locationManager.requestLocationUpdates("passive", gnssRate, 0.0f, nativeGnssListener, Looper.getMainLooper());
                }
                if (allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, gnssRate, 0.0f, nativeGnssListener, Looper.getMainLooper());
                }
            } else {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, gnssRate, 0.0f, nativeGnssListener, Looper.getMainLooper());
            }
        } catch (Exception ignored) {}

        // Seed from any existing kernel last-known location immediately if we have nothing yet
        if (lastValidPoint == null) {
            GpsPoint seed = queryLastKnownNativePoint(System.currentTimeMillis());
            if (seed != null) {
                saveValidPoint(seed);
                broadcastCoordinateUpdate();
            }
        }
    }

    private final LocationListener nativeGnssListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null || !isValidCoordinate(location.getLatitude(), location.getLongitude())) {
                return;
            }
            // If we already have a strong GPS satellite fix, ignore coarse network jumps (>100m)
            if (hasAcquiredLiveFix && !"gps".equalsIgnoreCase(location.getProvider())
                    && location.hasAccuracy() && location.getAccuracy() > 100.0f) {
                return;
            }

            long now = System.currentTimeMillis();
            float acc = location.hasAccuracy() ? location.getAccuracy() : 10.0f;
            GpsPoint pt = new GpsPoint(location.getLatitude(), location.getLongitude(), now, acc);
            latestHardwarePoint = pt;
            saveValidPoint(pt);

            // Immediately populate UI on the very first satellite fix, then switch to interval cadence
            if (!hasAcquiredLiveFix) {
                hasAcquiredLiveFix = true;
                startNativeGnssProviders();
                lastProcessedIntervalTime = now;
                if (isWorkingTime()) {
                    onPointSampled(pt);
                }
                broadcastCoordinateUpdate();
            }
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private void startIntervalTicker() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                processIntervalTick();
                long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
                mainHandler.postDelayed(this, interval);
            }
        }, 2000L);
    }

    // Runs strictly at the user's preset interval to sample coordinates, update UI box, and evaluate site moves
    private void processIntervalTick() {
        long now = System.currentTimeMillis();
        long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
        if (now - lastProcessedIntervalTime < (interval / 2L)) {
            return;
        }
        lastProcessedIntervalTime = now;

        GpsPoint sample = null;
        if (latestHardwarePoint != null) {
            sample = new GpsPoint(latestHardwarePoint.lat, latestHardwarePoint.lon, now, latestHardwarePoint.accuracy);
        } else {
            sample = queryLastKnownNativePoint(now);
            if (sample == null && lastValidPoint != null) {
                sample = new GpsPoint(lastValidPoint.lat, lastValidPoint.lon, now, lastValidPoint.accuracy);
            }
        }

        if (sample != null) {
            saveValidPoint(sample);
            broadcastCoordinateUpdate();
            if (isWorkingTime()) {
                onPointSampled(sample);
            }
        }
    }

    private void broadcastCoordinateUpdate() {
        Intent intent = new Intent(ACTION_LOCATION_UPDATED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
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

    private GpsPoint queryLastKnownNativePoint(long now) {
        if (locationManager == null) return null;
        try {
            List<String> providers = locationManager.getAllProviders();
            Location best = null;
            if (providers != null) {
                for (int i = 0; i < providers.size(); i++) {
                    Location l = locationManager.getLastKnownLocation(providers.get(i));
                    if (l != null && isValidCoordinate(l.getLatitude(), l.getLongitude())) {
                        if (best == null || l.getTime() > best.getTime()) {
                            best = l;
                        }
                    }
                }
            }
            if (best != null) {
                return new GpsPoint(best.getLatitude(), best.getLongitude(), now, best.hasAccuracy() ? best.getAccuracy() : 20.0f);
            }
        } catch (Exception ignored) {}
        return null;
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
            // Supports small radii like 0.5m (.5m) without clamping to 25m
            float threshold = Math.max(0.05f, prefs.getFloat("distance", 75.0f));
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

    // In-Memory Trajectory Engine supporting sub-meter (.5m) radii
    private void onPointSampled(GpsPoint pt) {
        float siteRadius = Math.max(0.05f, prefs.getFloat("distance", 75.0f));
        long pollIntervalMs = Math.max(1000L, prefs.getLong("interval", 60000L));
        int stayMins = prefs.getInt("stay_mins", 5);
        long stayRequiredMs = (stayMins <= 0) ? pollIntervalMs : (stayMins * 60000L);

        // 1. Lock initial starting site if none exists yet
        if (confirmedAnchor == null) {
            PlaceMatch match = getPlace(pt.lat, pt.lon);
            confirmedAnchor = new GpsPoint(pt.lat, pt.lon, pt.time, pt.accuracy);
            confirmedPlace = match;
            confirmedStayStart = pt.time;
            confirmedStayLastSeen = pt.time;
            persistAnchorState();
            return;
        }

        // 2. While no trip is active, check if still within current starting site
        if (memoryPoints.isEmpty()) {
            double distFromAnchor = distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, pt.lat, pt.lon);
            PlaceMatch currentMatch = getPlace(pt.lat, pt.lon);
            boolean sameKnownSite = confirmedPlace.isKnown && currentMatch.isKnown && confirmedPlace.name.equalsIgnoreCase(currentMatch.name);

            if (distFromAnchor <= siteRadius || sameKnownSite) {
                confirmedStayLastSeen = pt.time;
                persistAnchorState();
                return;
            }

            // Moved outside current site radius (even if .5m): start buffering in memory
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

        // 3. Currently moving: append to in-memory buffer
        memoryPoints.add(pt);

        double distFromCluster = distanceMeters(clusterCenter.lat, clusterCenter.lon, pt.lat, pt.lon);
        if (distFromCluster <= siteRadius) {
            if ((pt.time - clusterStartTime) >= stayRequiredMs) {
                commitBufferedTripIfValid(pt.time, siteRadius);
            }
        } else {
            clusterCenter = pt;
            clusterStartTime = pt.time;
        }
    }

    private void commitBufferedTripIfValid(long nowTime, float siteRadius) {
        if (confirmedAnchor == null || clusterCenter == null || memoryPoints.isEmpty()) return;

        PlaceMatch newPlace = getPlace(clusterCenter.lat, clusterCenter.lon);
        double destLat = newPlace.isKnown ? newPlace.lat : clusterCenter.lat;
        double destLon = newPlace.isKnown ? newPlace.lon : clusterCenter.lon;

        // Starting point coordinates
        double startLat = confirmedAnchor.lat;
        double startLon = confirmedAnchor.lon;

        double linearDist = distanceMeters(startLat, startLon, destLat, destLon);
        boolean sameKnownPlace = confirmedPlace.isKnown && newPlace.isKnown && confirmedPlace.name.equalsIgnoreCase(newPlace.name);
        boolean returnedToSameSite = linearDist <= siteRadius;

        if (sameKnownPlace || returnedToSameSite) {
            confirmedStayLastSeen = nowTime;
            persistAnchorState();
            memoryPoints.clear();
            clusterCenter = null;
            return;
        }

        double rollingDist = calculateRollingTripMeters(destLat, destLon, siteRadius);
        double finalDistMeters = Math.max(rollingDist, linearDist);

        long departTime = memoryPoints.get(0).time;
        long arriveTime = Math.max(departTime + 1000L, clusterStartTime);
        String eventType = buildTripName(confirmedPlace.name, newPlace.name);

        // Log event with starting point coordinates
        logEvent(eventType, startLat, startLon, departTime, arriveTime, finalDistMeters);

        confirmedAnchor = new GpsPoint(destLat, destLon, clusterStartTime, clusterCenter.accuracy);
        confirmedPlace = newPlace;
        confirmedStayStart = clusterStartTime;
        confirmedStayLastSeen = nowTime;
        persistAnchorState();
        memoryPoints.clear();
        clusterCenter = null;
    }

    private double calculateRollingTripMeters(double destLat, double destLon, float siteRadius) {
        if (memoryPoints.size() < 2) {
            return distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, destLat, destLon);
        }

        double rolling = 0.0;
        double minStep = Math.min(2.0, siteRadius * 0.25);
        GpsPoint prev = memoryPoints.get(0);

        for (int i = 1; i < memoryPoints.size(); i++) {
            GpsPoint curr = memoryPoints.get(i);
            if (curr.time > clusterStartTime && distanceMeters(destLat, destLon, curr.lat, curr.lon) <= siteRadius) {
                continue;
            }
            double seg = distanceMeters(prev.lat, prev.lon, curr.lat, curr.lon);
            if (seg >= minStep) {
                rolling += seg;
                prev = curr;
            }
        }

        double tail = distanceMeters(prev.lat, prev.lon, destLat, destLon);
        if (tail >= minStep) {
            rolling += tail;
        }
        return rolling;
    }

    private String buildTripName(String orig, String dest) {
        String o = (orig == null || orig.length() == 0) ? "SITE" : orig.toUpperCase(Locale.US);
        String d = (dest == null || dest.length() == 0) ? "SITE" : dest.toUpperCase(Locale.US);
        return o + " TO " + d;
    }

    private void logEvent(String type, double startLat, double startLon, long start, long end, double distanceMeters) {
        if (end <= start || distanceMeters <= 0.05) return;
        double hours = (end - start) / 3600000.0;
        double distKm = distanceMeters / 1000.0;
        double distMi = distanceMeters / 1609.344;
        db.execSQL("INSERT INTO events (type, lat, lon, start_time, end_time, duration_hours, dist_km, dist_mi) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{type, startLat, startLon, start, end, hours, distKm, distMi});
    }

    private boolean isWorkingTime() {
        int startHr = prefs.getInt("start_hour", 0);
        int endHr = prefs.getInt("end_hour", 24);
        if (startHr <= 0 && endHr >= 24) return true;
        if (startHr == endHr) return true;

        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (startHr < endHr) {
            return hour >= startHr && hour < endHr;
        } else {
            return hour >= startHr || hour < endHr;
        }
    }

    private boolean isLocalOrLanAddress(InetAddress addr) {
        if (addr == null) return false;
        return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
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
                        if (!isLocalOrLanAddress(socket.getInetAddress())) {
                            socket.close();
                            continue;
                        }
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
        try { locationManager.removeUpdates(nativeGnssListener); } catch (Exception ignored) {}

        if (!memoryPoints.isEmpty() && clusterCenter != null) {
            float siteRadius = Math.max(0.05f, prefs.getFloat("distance", 75.0f));
            commitBufferedTripIfValid(System.currentTimeMillis(), siteRadius);
        }
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
