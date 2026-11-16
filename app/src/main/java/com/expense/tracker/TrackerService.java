package com.expense.tracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
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

    private GpsPoint confirmedAnchor = null;
    private PlaceMatch confirmedPlace = null;
    private long confirmedStayStart = 0;
    private long confirmedStayLastSeen = 0;

    private final List<GpsPoint> memoryPoints = new ArrayList<GpsPoint>();
    private GpsPoint clusterCenter = null;
    private long clusterStartTime = 0;
    private long activeTripRowId = -1;

    private GpsPoint lastValidPoint = null;
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
                triggerSingleShotGpsPoll();
            } else if (ACTION_RELOAD_SETTINGS.equals(action)) {
                if (confirmedAnchor != null) {
                    confirmedPlace = getPlace(confirmedAnchor.lat, confirmedAnchor.lon);
                }
                long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
                scheduleNextPoll(interval);
                int newPort = prefs.getInt("http_port", 8080);
                if (newPort != currentServerPort) {
                    startHttpServer();
                }
            } else if (ACTION_ERASE_DATA.equals(action)) {
                synchronized (this) {
                    try { db.execSQL("DELETE FROM events"); } catch (Exception ignored) {}
                    memoryPoints.clear();
                    confirmedAnchor = null;
                    confirmedPlace = null;
                    clusterCenter = null;
                    activeTripRowId = -1;
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
                    .setContentText("Standalone GPS polling active.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 16) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Standalone GPS polling active.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = new Notification.Builder(this)
                    .setContentTitle("Travel Expense Tracker Active")
                    .setContentText("Standalone GPS polling active.")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
        } else {
            notification = new Notification(android.R.drawable.ic_menu_mylocation, "Travel Expense Tracker Active", System.currentTimeMillis());
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            try {
                Method m = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                m.invoke(notification, this, "Travel Expense Tracker Active", "Standalone GPS polling active.", pi);
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

    private void triggerSingleShotGpsPoll() {
        if (locationManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, null, getMainExecutor(), new java.util.function.Consumer<Location>() {
                    @Override
                    public void accept(Location location) {
                        handleSampledLocation(location);
                    }
                });
            } else {
                final LocationListener oneShot = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
                        handleSampledLocation(location);
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle e) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, oneShot, Looper.getMainLooper());
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, oneShot, Looper.getMainLooper());
                }
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try { locationManager.removeUpdates(oneShot); } catch (Exception ignored) {}
                        handleSampledLocation(getBestOfflineFallbackLocation());
                    }
                }, 10000L);
            }
        } catch (Exception ignored) {
            handleSampledLocation(getBestOfflineFallbackLocation());
        }
    }

    private void handleSampledLocation(Location loc) {
        long now = System.currentTimeMillis();
        GpsPoint pt = null;

        if (loc != null && isValidCoordinate(loc.getLatitude(), loc.getLongitude())) {
            float acc = loc.hasAccuracy() ? loc.getAccuracy() : 15.0f;
            pt = new GpsPoint(loc.getLatitude(), loc.getLongitude(), now, acc);
            saveValidPoint(pt);
        } else {
            Location fallback = getBestOfflineFallbackLocation();
            if (fallback != null && isValidCoordinate(fallback.getLatitude(), fallback.getLongitude())) {
                float acc = fallback.hasAccuracy() ? fallback.getAccuracy() : 20.0f;
                pt = new GpsPoint(fallback.getLatitude(), fallback.getLongitude(), now, acc);
            } else if (lastValidPoint != null) {
                pt = new GpsPoint(lastValidPoint.lat, lastValidPoint.lon, now, lastValidPoint.accuracy);
            }
        }

        if (pt != null) {
            broadcastCoordinateUpdate();
            if (isWorkingTime()) {
                onPointSampled(pt);
            }
        }
    }

    private void startIntervalTicker() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                triggerSingleShotGpsPoll();
                long interval = Math.max(1000L, prefs.getLong("interval", 60000L));
                mainHandler.postDelayed(this, interval);
            }
        }, 3000L);
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

    private Location getBestOfflineFallbackLocation() {
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
            return best;
        } catch (Exception ignored) {
            return null;
        }
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

    private synchronized void onPointSampled(GpsPoint pt) {
        float siteRadius = Math.max(0.05f, prefs.getFloat("distance", 75.0f));
        long pollIntervalMs = Math.max(1000L, prefs.getLong("interval", 60000L));
        int stayMins = prefs.getInt("stay_mins", 5);
        long stayRequiredMs = (stayMins <= 0) ? 0L : (stayMins * 60000L);

        if (confirmedAnchor == null) {
            PlaceMatch match = getPlace(pt.lat, pt.lon);
            confirmedAnchor = new GpsPoint(pt.lat, pt.lon, pt.time, pt.accuracy);
            confirmedPlace = match;
            confirmedStayStart = pt.time;
            confirmedStayLastSeen = pt.time;
            persistAnchorState();
            return;
        }

        if (memoryPoints.isEmpty()) {
            double distFromAnchor = distanceMeters(confirmedAnchor.lat, confirmedAnchor.lon, pt.lat, pt.lon);
            PlaceMatch currentMatch = getPlace(pt.lat, pt.lon);
            boolean sameKnownSite = confirmedPlace.isKnown && currentMatch.isKnown && confirmedPlace.name.equalsIgnoreCase(currentMatch.name);

            if (distFromAnchor < siteRadius || sameKnownSite) {
                confirmedStayLastSeen = pt.time;
                persistAnchorState();
                return;
            }

            long departTime = confirmedStayLastSeen;
            if (departTime <= 0 || (pt.time - departTime) > (pollIntervalMs * 3L)) {
                departTime = pt.time - pollIntervalMs;
            }
            memoryPoints.add(new GpsPoint(confirmedAnchor.lat, confirmedAnchor.lon, departTime, confirmedAnchor.accuracy));
            memoryPoints.add(pt);
            clusterCenter = pt;
            clusterStartTime = pt.time;
            activeTripRowId = -1;

            syncActiveTripToDb(pt, siteRadius);

            if (stayRequiredMs <= 0L) {
                finalizeActiveTrip(pt, siteRadius);
            }
            return;
        }

        memoryPoints.add(pt);
        syncActiveTripToDb(pt, siteRadius);

        float stationaryTolerance = Math.max(siteRadius, Math.min(20.0f, Math.max(6.0f, pt.accuracy)));
        double distFromCluster = distanceMeters(clusterCenter.lat, clusterCenter.lon, pt.lat, pt.lon);

        if (distFromCluster <= stationaryTolerance) {
            if ((pt.time - clusterStartTime) >= stayRequiredMs) {
                finalizeActiveTrip(pt, siteRadius);
            }
        } else {
            clusterCenter = pt;
            clusterStartTime = pt.time;
        }
    }

    private void syncActiveTripToDb(GpsPoint latestPt, float siteRadius) {
        if (confirmedAnchor == null || memoryPoints.size() < 2) return;

        GpsPoint destTarget = (clusterCenter != null) ? clusterCenter : latestPt;
        PlaceMatch newPlace = getPlace(destTarget.lat, destTarget.lon);
        double destLat = newPlace.isKnown ? newPlace.lat : latestPt.lat;
        double destLon = newPlace.isKnown ? newPlace.lon : latestPt.lon;

        double startLat = confirmedAnchor.lat;
        double startLon = confirmedAnchor.lon;

        double linearDist = distanceMeters(startLat, startLon, destLat, destLon);
        boolean sameKnownPlace = confirmedPlace.isKnown && newPlace.isKnown && confirmedPlace.name.equalsIgnoreCase(newPlace.name);

        if (sameKnownPlace || linearDist < siteRadius) {
            if (activeTripRowId != -1) {
                try {
                    db.delete("events", "id = ?", new String[]{String.valueOf(activeTripRowId)});
                } catch (Exception ignored) {}
                activeTripRowId = -1;
            }
            return;
        }

        double rollingDist = calculateRollingTripMeters(destLat, destLon, siteRadius);
        double finalDistMeters = Math.max(rollingDist, linearDist);

        long departTime = memoryPoints.get(0).time;
        long arriveTime = Math.max(departTime + 1000L, latestPt.time);
        String eventType = buildTripName(confirmedPlace.name, newPlace.name);

        double hours = (arriveTime - departTime) / 3600000.0;
        double distKm = finalDistMeters / 1000.0;
        double distMi = finalDistMeters / 1609.344;

        ContentValues cv = new ContentValues();
        cv.put("type", eventType);
        cv.put("lat", startLat);
        cv.put("lon", startLon);
        cv.put("start_time", departTime);
        cv.put("end_time", arriveTime);
        cv.put("duration_hours", hours);
        cv.put("dist_km", distKm);
        cv.put("dist_mi", distMi);

        try {
            if (activeTripRowId == -1) {
                activeTripRowId = db.insert("events", null, cv);
            } else {
                int updated = db.update("events", cv, "id = ?", new String[]{String.valueOf(activeTripRowId)});
                if (updated <= 0) {
                    activeTripRowId = db.insert("events", null, cv);
                }
            }
        } catch (Exception ignored) {}
    }

    private void finalizeActiveTrip(GpsPoint pt, float siteRadius) {
        if (confirmedAnchor == null || clusterCenter == null || memoryPoints.isEmpty()) return;

        PlaceMatch newPlace = getPlace(clusterCenter.lat, clusterCenter.lon);
        double destLat = newPlace.isKnown ? newPlace.lat : clusterCenter.lat;
        double destLon = newPlace.isKnown ? newPlace.lon : clusterCenter.lon;

        double startLat = confirmedAnchor.lat;
        double startLon = confirmedAnchor.lon;

        double linearDist = distanceMeters(startLat, startLon, destLat, destLon);
        boolean sameKnownPlace = confirmedPlace.isKnown && newPlace.isKnown && confirmedPlace.name.equalsIgnoreCase(newPlace.name);

        if (sameKnownPlace || linearDist < siteRadius) {
            if (activeTripRowId != -1) {
                try {
                    db.delete("events", "id = ?", new String[]{String.valueOf(activeTripRowId)});
                } catch (Exception ignored) {}
                activeTripRowId = -1;
            }
            confirmedStayLastSeen = pt.time;
            persistAnchorState();
            memoryPoints.clear();
            clusterCenter = null;
            return;
        }

        syncActiveTripToDb(clusterCenter, siteRadius);

        confirmedAnchor = new GpsPoint(destLat, destLon, clusterStartTime, clusterCenter.accuracy);
        confirmedPlace = newPlace;
        confirmedStayStart = clusterStartTime;
        confirmedStayLastSeen = pt.time;
        persistAnchorState();

        activeTripRowId = -1;
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
            if (clusterCenter != null && curr.time > clusterStartTime && distanceMeters(destLat, destLon, curr.lat, curr.lon) <= siteRadius) {
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

    private static String formatMetric(double val) {
        if (val > 0.0 && val < 0.01) {
            return String.format(Locale.US, "%.4f", val);
        }
        return String.format(Locale.US, "%.2f", val);
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
                qEnd = parseFormat.parse(params[2]).getTime() + 59999L;
                if (params[2].endsWith("1700") && params[1].length() >= 8 && params[2].startsWith(params[1].substring(0, 8))) {
                    if (System.currentTimeMillis() > qEnd) {
                        qEnd = parseFormat.parse(params[1].substring(0, 8) + "2359").getTime() + 59999L;
                    }
                }
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
                   .append(formatMetric(c.getDouble(5))).append(",")
                   .append(formatMetric(c.getDouble(6))).append(",")
                   .append(formatMetric(c.getDouble(7))).append("\n");
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

        if (!memoryPoints.isEmpty() && clusterCenter != null) {
            float siteRadius = Math.max(0.05f, prefs.getFloat("distance", 75.0f));
            finalizeActiveTrip(clusterCenter, siteRadius);
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
