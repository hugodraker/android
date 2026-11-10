package com.expense.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class TrackerService extends Service {
    private LocationManager locationManager;
    private SQLiteDatabase db;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private SharedPreferences prefs;

    // State Machine
    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_MOVING = 1;
    private static final int STATE_STATIONARY = 2;
    private static final int STATE_IGNORED = 3;

    private int currentState = STATE_UNKNOWN;
    private long stateStartTime = 0;
    private Location lastLocation = null;
    private long lastLocationTime = 0;
    private Location anchorLocation = null;

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
        builder.setContentTitle("Travel Expense Tracker")
               .setContentText("Recording site durations and travel time.")
               .setSmallIcon(android.R.drawable.ic_menu_mylocation)
               .setContentIntent(pi)
               .setOngoing(true);

        startForeground(1, Build.VERSION.SDK_INT >= 16 ? builder.build() : builder.getNotification());
    }

    private void startLocationTracking() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        long minTime = prefs.getLong("interval", 30000); 
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, 0, locationListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTime, 0, locationListener);
        } catch (SecurityException e) {}
    }

    private boolean isInIgnoreZone(Location loc) {
        String ignoreStr = prefs.getString("ignore_zones", "");
        if (ignoreStr.isEmpty()) return false;
        String[] pairs = ignoreStr.split(";");
        float threshold = prefs.getFloat("distance", 50.0f);
        for (String pair : pairs) {
            String[] coords = pair.split(",");
            if (coords.length == 2) {
                try {
                    Location ignoreLoc = new Location("");
                    ignoreLoc.setLatitude(Double.parseDouble(coords[0].trim()));
                    ignoreLoc.setLongitude(Double.parseDouble(coords[1].trim()));
                    if (loc.distanceTo(ignoreLoc) <= threshold) return true;
                } catch (Exception e) {}
            }
        }
        return false;
    }

    private void finalizeCurrentState(long endTime) {
        if (currentState == STATE_UNKNOWN || currentState == STATE_IGNORED || stateStartTime == 0) return;
        
        double durationHours = (endTime - stateStartTime) / 3600000.0;
        
        // Only log events longer than 1 minute to avoid jitter noise
        if ((endTime - stateStartTime) > 60000) {
            if (currentState == STATE_STATIONARY && anchorLocation != null) {
                db.execSQL("INSERT INTO events (type, lat, lon, start_time, end_time, duration_hours) VALUES ('STAY', ?, ?, ?, ?, ?)",
                        new Object[]{anchorLocation.getLatitude(), anchorLocation.getLongitude(), stateStartTime, endTime, durationHours});
            } else if (currentState == STATE_MOVING) {
                db.execSQL("INSERT INTO events (type, lat, lon, start_time, end_time, duration_hours) VALUES ('TRAVEL', 0, 0, ?, ?, ?)",
                        new Object[]{stateStartTime, endTime, durationHours});
            }
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!isWorkingTime()) return;
            long now = System.currentTimeMillis();
            
            if (isInIgnoreZone(location)) {
                if (currentState != STATE_IGNORED) {
                    finalizeCurrentState(now);
                    currentState = STATE_IGNORED;
                    stateStartTime = now;
                }
                return;
            }

            if (currentState == STATE_IGNORED || currentState == STATE_UNKNOWN) {
                currentState = STATE_MOVING;
                stateStartTime = now;
                lastLocation = location;
                lastLocationTime = now;
                return;
            }

            float distThreshold = prefs.getFloat("distance", 50.0f);
            
            if (currentState == STATE_MOVING) {
                if (location.distanceTo(lastLocation) <= distThreshold) {
                    // Check if we've been stationary for 5 minutes (300000 ms)
                    if ((now - lastLocationTime) >= 300000) {
                        finalizeCurrentState(lastLocationTime); // End travel
                        currentState = STATE_STATIONARY;
                        stateStartTime = lastLocationTime;
                        anchorLocation = lastLocation; // Lock in the site
                    }
                } else {
                    lastLocation = location;
                    lastLocationTime = now;
                }
            } else if (currentState == STATE_STATIONARY) {
                if (location.distanceTo(anchorLocation) > distThreshold) {
                    // Left the site
                    finalizeCurrentState(now);
                    currentState = STATE_MOVING;
                    stateStartTime = now;
                    lastLocation = location;
                    lastLocationTime = now;
                } else {
                    lastLocationTime = now; // Still here
                }
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private boolean isWorkingTime() {
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        return hour >= prefs.getInt("start_hour", 9) && hour < prefs.getInt("end_hour", 17);
    }

    private void startHttpServer() {
        isRunning = true;
        int port = prefs.getInt("http_port", 8080);
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                while (isRunning) handleRequest(serverSocket.accept());
            } catch (Exception e) {}
        }).start();
    }

    private void handleRequest(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            String line = in.readLine();
            if (line == null) return;

            String authHeader = null;
            String reqLine;
            while ((reqLine = in.readLine()) != null && !reqLine.isEmpty()) {
                if (reqLine.toLowerCase().startsWith("authorization: basic ")) authHeader = reqLine.substring(21);
            }

            String expectedAuth = Base64.encodeToString((prefs.getString("http_user", "admin") + ":" + prefs.getString("http_pass", "password")).getBytes(), Base64.NO_WRAP);

            if (authHeader == null || !authHeader.equals(expectedAuth)) {
                out.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Tracker\"\r\n\r\n".getBytes());
            } else if (line.startsWith("GET / ")) {
                StringBuilder csv = new StringBuilder("Event Type,Latitude,Longitude,Start Time,End Time,Duration (Hours)\n");
                Cursor c = db.rawQuery("SELECT * FROM events ORDER BY start_time DESC", null);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                while (c.moveToNext()) {
                    csv.append(c.getString(1)).append(",")
                       .append(c.getDouble(2)).append(",")
                       .append(c.getDouble(3)).append(",")
                       .append(sdf.format(new Date(c.getLong(4)))).append(",")
                       .append(sdf.format(new Date(c.getLong(5)))).append(",")
                       .append(String.format(Locale.US, "%.2f", c.getDouble(6))).append("\n");
                }
                c.close();
                String data = csv.toString();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/csv\r\nContent-Length: " + data.length() + "\r\n\r\n" + data).getBytes());
            }
            out.flush();
            socket.close();
        } catch (Exception e) {}
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        finalizeCurrentState(System.currentTimeMillis()); // Finalize tracking segment on exit
        if (locationManager != null) locationManager.removeUpdates(locationListener);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
        if (db != null) db.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
