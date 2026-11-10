package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Today's weather after the date on Home ("  ·  ☀ 28°"), from Open-Meteo (free, no key, no account).
 * The place is "latitude"/"longitude" in the theme's config.json, else config.xml's; with neither, nothing shows.
 * Fetched at most every 30 minutes on a background thread; the last reading is kept for an offline Home.
 */
final class Weather {

    private static final String PREFS = "namaz_times";
    private static final long MAX_AGE_MS = 30 * 60_000L;
    private static volatile boolean fetching;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Weather() {}

    /** Shows the cached reading at once and fetches a fresh one when it is stale. */
    static void update(Context c, TextView view) {
        if (view == null) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String shown = p.getString("weather_text", "");
        view.setText(shown.isEmpty() ? "" : "  ·  " + shown);
        view.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        double[] at = location(c);
        if (at == null || fetching) return;
        if (System.currentTimeMillis() - p.getLong("weather_at", 0) < MAX_AGE_MS) return;
        fetching = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                String text = fetch(at[0], at[1]);
                if (text != null) {
                    p.edit().putString("weather_text", text).putLong("weather_at", System.currentTimeMillis()).apply();
                    main.post(() -> {
                        view.setText("  ·  " + text);
                        view.setVisibility(View.VISIBLE);
                    });
                }
            } finally {
                fetching = false;
            }
        }, "weather").start();
    }

    /** {latitude, longitude} from the theme's config.json, else config.xml; null when unset. */
    static double[] location(Context c) {
        try {
            File f = new File(c.getExternalFilesDir(null), "config.json");
            if (f.isFile()) {
                JSONObject json = new JSONObject(read(new FileInputStream(f)));
                if (json.has("latitude") && json.has("longitude")) {
                    return new double[]{json.getDouble("latitude"), json.getDouble("longitude")};
                }
            }
        } catch (Exception ignored) {
            // fall back to config.xml
        }
        try {
            String lat = c.getString(R.string.latitude).trim(), lon = c.getString(R.string.longitude).trim();
            if (!lat.isEmpty() && !lon.isEmpty()) return new double[]{Double.parseDouble(lat), Double.parseDouble(lon)};
        } catch (Exception ignored) {
            // unusable
        }
        return null;
    }

    private static String fetch(double lat, double lon) {
        HttpURLConnection http = null;
        try {
            URL url = new URL(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon));
            http = (HttpURLConnection) url.openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(8000);
            if (http.getResponseCode() != 200) return null;
            JSONObject cur = new JSONObject(read(http.getInputStream())).getJSONObject("current");
            int temp = (int) Math.round(cur.getDouble("temperature_2m"));
            boolean day = cur.optInt("is_day", 1) == 1;
            return icon(cur.optInt("weather_code", 0), day) + " " + temp + "°";
        } catch (Exception e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    /** WMO weather code to an emoji. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";            // sun / moon
        if (code <= 2) return day ? "🌤️" : "☁️";      // sun behind cloud / cloud
        if (code == 3) return "☁️";                                    // cloud
        if (code == 45 || code == 48) return "🌫️";              // fog
        if (code >= 95) return "⛈️";                                   // thunderstorm
        if (code >= 80) return "🌦️";                            // showers
        if (code >= 71 && code <= 77) return "❄️";                     // snow
        if (code >= 51) return "🌧️";                            // rain
        return "☁️";
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
