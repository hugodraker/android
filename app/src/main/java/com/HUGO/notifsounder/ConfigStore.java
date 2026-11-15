package com.HUGO.notifsounder;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class ConfigStore {

    public static class SoundProfile {
        public String name = "Default";
        public String soundPath = ""; // .mp3 or .mid/.midi
        public int volumePercent = 100; // 0 - 100
        public boolean ignoreDnd = false;
        public boolean vibrate = true;
    }

    public static class NotifEvent {
        public String name = "Rule";
        public boolean enabled = true;
        public String appFilter = "*"; // Package name or "*" for all apps
        public String matchText = "";  // Substring to match (case-insensitive)
        public String profileName = "Default";
        public boolean repeatUntilAck = false; // Disabled by default
        public String luaScriptPath = ""; // Optional .lua script to execute on match
    }

    public boolean monitorEnabled = true;
    public boolean runOnStart = true;
    public final List<SoundProfile> profiles = new ArrayList<SoundProfile>();
    public final List<NotifEvent> events = new ArrayList<NotifEvent>();

    public static File getIniFile(Context ctx) {
        return new File(ctx.getFilesDir(), "notifsounds.ini");
    }

    public synchronized void load(Context ctx) {
        profiles.clear();
        events.clear();
        File f = getIniFile(ctx);
        if (!f.exists()) {
            SoundProfile def = new SoundProfile();
            def.name = "Default";
            def.volumePercent = 100;
            def.vibrate = true;
            profiles.add(def);
            return;
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            String sec = "";
            SoundProfile curProf = null;
            NotifEvent curEv = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    sec = line.substring(1, line.length() - 1).trim().toUpperCase();
                    curProf = null;
                    curEv = null;
                    if (sec.startsWith("PROFILE_")) {
                        curProf = new SoundProfile();
                        profiles.add(curProf);
                    } else if (sec.startsWith("EVENT_")) {
                        curEv = new NotifEvent();
                        events.add(curEv);
                    }
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();

                if ("GLOBAL".equals(sec)) {
                    if ("monitor_enabled".equals(k)) monitorEnabled = !"0".equals(v);
                    else if ("run_on_start".equals(k)) runOnStart = !"0".equals(v);
                } else if (curProf != null) {
                    if ("name".equals(k)) curProf.name = v;
                    else if ("sound_path".equals(k)) curProf.soundPath = v;
                    else if ("volume".equals(k)) curProf.volumePercent = parseInt(v, 100);
                    else if ("ignore_dnd".equals(k)) curProf.ignoreDnd = "1".equals(v);
                    else if ("vibrate".equals(k)) curProf.vibrate = !"0".equals(v);
                } else if (curEv != null) {
                    if ("name".equals(k)) curEv.name = v;
                    else if ("enabled".equals(k)) curEv.enabled = !"0".equals(v);
                    else if ("app_filter".equals(k)) curEv.appFilter = v.length() > 0 ? v : "*";
                    else if ("match_text".equals(k)) curEv.matchText = v;
                    else if ("profile".equals(k)) curEv.profileName = v;
                    else if ("repeat_ack".equals(k)) curEv.repeatUntilAck = "1".equals(v);
                    else if ("lua_script".equals(k)) curEv.luaScriptPath = v;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }

        if (profiles.isEmpty()) {
            SoundProfile def = new SoundProfile();
            def.name = "Default";
            profiles.add(def);
        }
    }

    public synchronized void save(Context ctx) {
        File f = getIniFile(ctx);
        FileOutputStream fos = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[GLOBAL]\r\n");
            sb.append("monitor_enabled=").append(monitorEnabled ? "1" : "0").append("\r\n");
            sb.append("run_on_start=").append(runOnStart ? "1" : "0").append("\r\n\r\n");

            for (int i = 0; i < profiles.size(); i++) {
                SoundProfile p = profiles.get(i);
                sb.append("[PROFILE_").append(i).append("]\r\n");
                sb.append("name=").append(clean(p.name)).append("\r\n");
                sb.append("sound_path=").append(clean(p.soundPath)).append("\r\n");
                sb.append("volume=").append(p.volumePercent).append("\r\n");
                sb.append("ignore_dnd=").append(p.ignoreDnd ? "1" : "0").append("\r\n");
                sb.append("vibrate=").append(p.vibrate ? "1" : "0").append("\r\n\r\n");
            }

            for (int i = 0; i < events.size(); i++) {
                NotifEvent ev = events.get(i);
                sb.append("[EVENT_").append(i).append("]\r\n");
                sb.append("name=").append(clean(ev.name)).append("\r\n");
                sb.append("enabled=").append(ev.enabled ? "1" : "0").append("\r\n");
                sb.append("app_filter=").append(clean(ev.appFilter)).append("\r\n");
                sb.append("match_text=").append(clean(ev.matchText)).append("\r\n");
                sb.append("profile=").append(clean(ev.profileName)).append("\r\n");
                sb.append("repeat_ack=").append(ev.repeatUntilAck ? "1" : "0").append("\r\n");
                sb.append("lua_script=").append(clean(ev.luaScriptPath)).append("\r\n\r\n");
            }

            fos = new FileOutputStream(f, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    public SoundProfile findProfile(String name) {
        for (int i = 0; i < profiles.size(); i++) {
            SoundProfile p = profiles.get(i);
            if (p.name.equalsIgnoreCase(name)) return p;
        }
        return profiles.get(0);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\r", "").replace("\n", "").trim();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
