package com.HUGO.notifsounder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;

import java.lang.reflect.Field;
import java.util.List;

public class NotifMonitorService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return;
        }

        ConfigStore store = new ConfigStore();
        store.load(this);
        if (!store.monitorEnabled) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString().trim() : "";
        if (pkg.equalsIgnoreCase(getPackageName())) return;

        String fullText = extractNotificationText(event);
        if (fullText.length() == 0) return;

        String lowerText = fullText.toLowerCase();
        String lowerPkg = pkg.toLowerCase();

        for (int i = 0; i < store.events.size(); i++) {
            ConfigStore.NotifEvent rule = store.events.get(i);
            if (!rule.enabled) continue;

            String filter = rule.appFilter != null ? rule.appFilter.trim().toLowerCase() : "*";
            if (filter.length() > 0 && !"*".equals(filter)) {
                if (!lowerPkg.contains(filter)) continue;
            }

            String needle = rule.matchText != null ? rule.matchText.trim().toLowerCase() : "";
            if (needle.length() == 0 || lowerText.contains(needle)) {
                ConfigStore.SoundProfile prof = store.findProfile(rule.profileName);
                Intent playIntent = new Intent(this, AlertPlayerService.class);
                playIntent.putExtra("action", "PLAY");
                playIntent.putExtra("sound_path", prof.soundPath);
                playIntent.putExtra("volume", prof.volumePercent);
                playIntent.putExtra("ignore_dnd", prof.ignoreDnd);
                playIntent.putExtra("vibrate", prof.vibrate);
                playIntent.putExtra("repeat_ack", rule.repeatUntilAck);
                playIntent.putExtra("rule_name", rule.name);
                playIntent.putExtra("profile_name", prof.name);
                playIntent.putExtra("notif_pkg", pkg);
                playIntent.putExtra("matched_text", fullText);
                playIntent.putExtra("lua_script", rule.luaScriptPath != null ? rule.luaScriptPath : "");
                BootReceiver.startServiceCompat(this, playIntent);
                break;
            }
        }
    }

    private String extractNotificationText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        try {
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (int i = 0; i < texts.size(); i++) {
                    CharSequence cs = texts.get(i);
                    if (cs != null) sb.append(cs.toString()).append(" ");
                }
            }
        } catch (Throwable ignored) {}

        try {
            Parcelable p = event.getParcelableData();
            if (p instanceof Notification) {
                Notification n = (Notification) p;
                if (n.tickerText != null) {
                    sb.append(n.tickerText.toString()).append(" ");
                }
                Field extrasField = Notification.class.getField("extras");
                Object extrasObj = extrasField.get(n);
                if (extrasObj instanceof Bundle) {
                    Bundle b = (Bundle) extrasObj;
                    CharSequence title = b.getCharSequence("android.title");
                    CharSequence text = b.getCharSequence("android.text");
                    CharSequence bigText = b.getCharSequence("android.bigText");
                    if (title != null) sb.append(title.toString()).append(" ");
                    if (text != null) sb.append(text.toString()).append(" ");
                    if (bigText != null) sb.append(bigText.toString()).append(" ");
                }
            }
        } catch (Throwable ignored) {}

        return sb.toString().trim();
    }

    @Override
    public void onInterrupt() {}
}
