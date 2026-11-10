//travel time onsite time tracker 2026 HUGO
//RELEASED INTO THE PUBLIC DOMAIN
package com.expense.tracker;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TrackerService extends Service {
    private LocationManager locationManager;
    private SQLiteDatabase db;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private SharedPreferences prefs;

    private Handler pollHandler;
    private Runnable pollRunnable;

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_MOVING = 1;
    private static final int STATE_STATIONARY = 2;

    private int currentState = STATE_UNKNOWN;
    private long stateStartTime = 0;
    private Location lastLocation = null;
    private long lastLocationTime = 0;
    
    // Naming logic
    private Location anchorLocation = null;
    private String currentPlaceName = "SITE";
    private String originName = "SITE";

    // Security Lockout
    private static long blockConnectionsUntil = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("TrackerPrefs", MODE_PRIVATE);
        db = new SQLiteOpenHelper(this, "travel_events.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, lat REAL, lon REAL, start_time INTEGER, end_time INTEGER, duration_hours REAL)");
            }
            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
        }.getWritableDatabase();

        startForegroundService();
        startLocationTracking();
        startHttpServer();
    }

    private void startForegroundService() {
        String channelId = "travel_tracker_channel";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(channelId, "Travel Tracking", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channelId) : new Notification.Builder(this);
        builder.setContentTitle("Travel Expense Tracker Active")
               .setSmallIcon(android.R.drawable.ic_menu_mylocation)
               .setContentIntent(pi)
               .setOngoing(true);

        Notification notification = Build.VERSION.SDK_INT >= 16 ? builder.build() : builder.getNotification();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }
    }

    private void startLocationTracking() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        pollHandler = new Handler(Looper.getMainLooper());
        
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, oneShotListener);
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, oneShotListener);
                } catch (SecurityException e) {}
                
                long interval = prefs.getLong("interval", 30000);
                if (interval < 5000) interval = 5000; 
                pollHandler.postDelayed(this, interval);
            }
        };
        pollHandler.post(pollRunnable);
    }

    private final LocationListener oneShotListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            locationManager.removeUpdates(this);
            processLocation(location);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private class PlaceMatch {
        String name;
        boolean omitted;
        PlaceMatch(String n, boolean o) { name = n; omitted = o; }
    }

    private PlaceMatch getPlace(Location loc) {
        String saved = prefs.getString("special_locations", "");
        if (saved.isEmpty()) return new PlaceMatch("SITE", false);
        
        float threshold = prefs.getFloat("distance", 50.0f);
        for (String entry : saved.split(";")) {
            String[] parts = entry.split("\\|");
            if (parts.length == 3) {
                try {
                    String[] coords = parts[1].split(",");
                    Location sLoc = new Location("");
                    sLoc.setLatitude(Double.parseDouble(coords[0].trim()));
                    sLoc.setLongitude(Double.parseDouble(coords[1].trim()));
                    if (loc.distanceTo(sLoc) <= threshold) {
                        return new PlaceMatch(parts[0], Boolean.parseBoolean(parts[2]));
                    }
                } catch (Exception e) {}
            }
        }
        return new PlaceMatch("SITE", false);
    }

    private void processLocation(Location location) {
        long now = System.currentTimeMillis();
        float distThreshold = prefs.getFloat("distance", 50.0f);
        
        if (currentState == STATE_UNKNOWN) {
            currentState = STATE_MOVING;
            stateStartTime = now;
            lastLocation = location;
            lastLocationTime = now;
            originName = getPlace(location).name;
            return;
        }

        if (currentState == STATE_MOVING) {
            if (location.distanceTo(lastLocation) <= distThreshold) {
                // Stationary for 5 mins
                if ((now - lastLocationTime) >= 300000) {
                    PlaceMatch dest = getPlace(lastLocation);
                    
                    // Log Travel Event
                    String tripName = buildTripName(originName, dest.name);
                    logEvent(tripName, 0, 0, stateStartTime, lastLocationTime);
                    
                    // Transition to Stationary
                    currentState = STATE_STATIONARY;
                    stateStartTime = lastLocationTime;
                    anchorLocation = lastLocation;
                    currentPlaceName = dest.name;
                }
            } else {
                lastLocation = location;
                lastLocationTime = now;
            }
        } else if (currentState == STATE_STATIONARY) {
            if (location.distanceTo(anchorLocation) > distThreshold) {
                PlaceMatch origin = getPlace(anchorLocation);
                
                // Log Stay Event if not omitted
                if (!origin.omitted) {
                    logEvent("STAY AT " + currentPlaceName, anchorLocation.getLatitude(), anchorLocation.getLongitude(), stateStartTime, now);
                }
                
                // Transition to Moving
                currentState = STATE_MOVING;
                stateStartTime = now;
                lastLocation = location;
                lastLocationTime = now;
                originName = currentPlaceName;
            } else {
                lastLocationTime = now;
            }
        }
    }

    private String buildTripName(String orig, String dest) {
        if (orig.equals("SITE") && dest.equals("SITE")) return "SITE TO SITE";
        if (orig.equals("SITE")) return "TO " + dest;
        if (dest.equals("SITE")) return "FROM " + orig;
        return orig + " TO " + dest;
    }

    private void logEvent(String type, double lat, double lon, long start, long end) {
        if ((end - start) < 60000) return; // ignore sub-minute noise
        double hours = (end - start) / 3600000.0;
        db.execSQL("INSERT INTO events (type, lat, lon, start_time, end_time, duration_hours) VALUES (?, ?, ?, ?, ?, ?)",
                new Object[]{type, lat, lon, start, end, hours});
    }

    private void startHttpServer() {
        isRunning = true;
        int port = prefs.getInt("http_port", 8080);
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    handleRequest(socket);
                }
            } catch (Exception e) {}
        }).start();
    }

    private void handleRequest(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            String requestLine = in.readLine();
            
            if (requestLine == null) return;
            if (System.currentTimeMillis() < blockConnectionsUntil) {
                socket.close(); // Hard drop if blocked
                return;
            }

            // Expected format: GET /download?admin=password&202601010000&202601020000 HTTP/1.1
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
                blockConnectionsUntil = System.currentTimeMillis() + (3 * 60 * 1000); // 3 minutes lockout
                out.write("HTTP/1.1 403 Forbidden\r\n\r\nBad Credentials. IP Locked for 3 mins.".getBytes());
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

            StringBuilder csv = new StringBuilder("Event Type,Latitude,Longitude,Start Time,End Time,Duration (Hours)\n");
            Cursor c = db.rawQuery("SELECT * FROM events WHERE start_time >= ? AND start_time <= ? ORDER BY start_time DESC", 
                    new String[]{String.valueOf(qStart), String.valueOf(qEnd)});
            
            SimpleDateFormat outFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            while (c.moveToNext()) {
                csv.append(c.getString(1)).append(",")
                   .append(c.getDouble(2)).append(",")
                   .append(c.getDouble(3)).append(",")
                   .append(outFormat.format(new Date(c.getLong(4)))).append(",")
                   .append(outFormat.format(new Date(c.getLong(5)))).append(",")
                   .append(String.format(Locale.US, "%.2f", c.getDouble(6))).append("\n");
            }
            c.close();
            
            String data = csv.toString();
            String filename = params[1].substring(0, 8) + "-tracker.csv"; // e.g. 20260101-tracker.csv

            String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/csv\r\n" +
                            "Content-Disposition: attachment; filename=\"" + filename + "\"\r\n" +
                            "Content-Length: " + data.length() + "\r\n\r\n";
            
            out.write(header.getBytes());
            out.write(data.getBytes());
            out.flush();
            socket.close();
        } catch (Exception e) {}
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (currentState != STATE_UNKNOWN) {
            logEvent("TRACKING STOPPED AT " + currentPlaceName, 0, 0, stateStartTime, System.currentTimeMillis());
        }
        if (pollHandler != null && pollRunnable != null) pollHandler.removeCallbacks(pollRunnable);
        if (locationManager != null) locationManager.removeUpdates(oneShotListener);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
