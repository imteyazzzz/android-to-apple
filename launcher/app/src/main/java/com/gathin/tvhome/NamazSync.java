package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Namaz and Ramadan times from Supabase (docs/supabase/schema.sql), edited on a phone (docs/phone/index.html).
 * The TV reads the single row of public.tv_namaz with the project's anon key, every 15 minutes and whenever
 * Home comes up, and applies it only when its updated_at changed. The project is "supabase_url" and
 * "supabase_key" in the theme's config.json; without them nothing is fetched and the remote's settings stay.
 */
final class NamazSync {

    static final String ACTION_SYNC = "com.gathin.tvhome.NAMAZ_SYNC";
    private static final String PREFS = "namaz_times";
    private static final String APPLIED = "sync_applied_at";
    private static final String CHECKED = "sync_checked_at";
    private static final long MIN_GAP_MS = 2 * 60_000L;
    private static volatile boolean running;

    private NamazSync() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** {url, anon key} from the theme's config.json, or null. */
    static String[] project(Context c) {
        try {
            File f = new File(c.getExternalFilesDir(null), "config.json");
            if (!f.isFile()) return null;
            JSONObject json = new JSONObject(read(new FileInputStream(f)));
            String url = json.optString("supabase_url", "").trim();
            String key = json.optString("supabase_key", "").trim();
            if (url.isEmpty() || key.isEmpty()) return null;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            return new String[]{url, key};
        } catch (Exception e) {
            return null;
        }
    }

    /** A repeating, inexact wake-up every 15 minutes (NamazAlarmReceiver runs the fetch). */
    static void scheduleRepeating(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 4713,
                new Intent(c, NamazAlarmReceiver.class).setAction(ACTION_SYNC),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (project(c) == null) {
            am.cancel(pi);
            return;
        }
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000L,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }

    /** Fetches on a background thread (at most every 2 minutes); `done` runs after, also when nothing changed. */
    static void fetchAsync(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (running || now - prefs(app).getLong(CHECKED, 0) < MIN_GAP_MS || project(app) == null) {
            if (done != null) done.run();
            return;
        }
        running = true;
        prefs(app).edit().putLong(CHECKED, now).apply();
        new Thread(() -> {
            try {
                fetch(app);
            } finally {
                running = false;
                if (done != null) done.run();
            }
        }, "namaz-sync").start();
    }

    /** True when new data was applied. */
    static boolean fetch(Context c) {
        String[] p = project(c);
        if (p == null) return false;
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(p[0] + "/rest/v1/tv_namaz?id=eq.1&select=data,updated_at")
                    .openConnection();
            http.setConnectTimeout(10_000);
            http.setReadTimeout(10_000);
            http.setRequestProperty("apikey", p[1]);
            http.setRequestProperty("Authorization", "Bearer " + p[1]);
            http.setRequestProperty("Accept", "application/json");
            if (http.getResponseCode() != 200) return false;
            String body = read(http.getInputStream()).trim();
            if (!body.startsWith("[") || body.equals("[]")) return false;
            JSONObject row = new org.json.JSONArray(body).getJSONObject(0);
            String updated = row.optString("updated_at", "");
            if (updated.equals(prefs(c).getString(APPLIED, ""))) return false;
            apply(c, row.getJSONObject("data"));
            prefs(c).edit().putString(APPLIED, updated).apply();
            NamazAlarm.schedule(c);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static void apply(Context c, JSONObject d) {
        for (int i = 0; i < NamazTimes.KEYS.length; i++) {
            int m = minutes(d.optString(NamazTimes.KEYS[i], ""));
            if (m >= 0) NamazTimes.set(c, i, m);
        }
        if (d.has("show_on_screensaver")) NamazTimes.setShowOnScreensaver(c, d.optBoolean("show_on_screensaver", true));
        if (d.has("alert_on")) NamazAlarm.setEnabled(c, d.optBoolean("alert_on", true));
        if (d.has("alert_before_min")) NamazAlarm.setMinutesBefore(c, d.optInt("alert_before_min", 5));
        if (d.has("lock_min")) NamazAlarm.setCoverMinutes(c, d.optInt("lock_min", 15));

        String start = d.optString("ramadan_start", "");
        if (start.matches("\\d{4}-\\d{2}-\\d{2}")) {
            RamadanTimes.setStart(c, Integer.parseInt(start.substring(0, 4)),
                    Integer.parseInt(start.substring(5, 7)), Integer.parseInt(start.substring(8, 10)));
        }
        if (d.has("ramadan_days")) RamadanTimes.setDays(c, d.optInt("ramadan_days", 30));
        String[][] ends = {{"sehri_first", "sehri_last"}, {"iftar_first", "iftar_last"}};
        int[] which = {RamadanTimes.SEHRI, RamadanTimes.IFTAR};
        for (int w = 0; w < 2; w++) {
            for (int e = 0; e < 2; e++) {
                int m = minutes(d.optString(ends[w][e], ""));
                if (m >= 0) RamadanTimes.setEndpoint(c, which[w], e, m);
            }
        }
        if (d.has("iftar_countdown_min")) RamadanTimes.setCountdownMinutes(c, d.optInt("iftar_countdown_min", 10));
    }

    /** "05:30" (24 h) to minutes after midnight; -1 when not a time. */
    static int minutes(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return -1;
        String[] t = hhmm.split(":");
        int h = Integer.parseInt(t[0]), m = Integer.parseInt(t[1]);
        return h < 24 && m < 60 ? h * 60 + m : -1;
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
