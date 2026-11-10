package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Ramadan mode. The owner sets the first day (moved a day either way once the moon is sighted), the number of
 * fasts, and the masjid calendar's Sehri and Iftar times for the first and the last fast; the days between are
 * interpolated (the times move about a minute a day, steadily). Any single day can be corrected on its own.
 */
final class RamadanTimes {

    private static final String PREFS = "namaz_times";
    private static final String START = "ramadan_start";         // yyyymmdd
    private static final String DAYS = "ramadan_days";
    private static final String SEHRI_FIRST = "sehri_first", SEHRI_LAST = "sehri_last";
    private static final String IFTAR_FIRST = "iftar_first", IFTAR_LAST = "iftar_last";
    private static final String COUNTDOWN = "iftar_countdown_min";
    /** Minutes the Iftar screen stays locked after Iftar, for the dua. */
    static final int AFTER_IFTAR_MIN = 5;

    private RamadanTimes() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- settings ----

    /** Expected start of Ramadan 1448 in India; the owner moves it once the moon is sighted. */
    static Calendar start(Context c) {
        int v = prefs(c).getInt(START, 20270209);
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(v / 10000, (v / 100) % 100 - 1, v % 100);
        return cal;
    }

    static void shiftStart(Context c, int days) {
        Calendar s = start(c);
        s.add(Calendar.DAY_OF_MONTH, days);
        prefs(c).edit().putInt(START, s.get(Calendar.YEAR) * 10000 + (s.get(Calendar.MONTH) + 1) * 100
                + s.get(Calendar.DAY_OF_MONTH)).apply();
    }

    static void setStart(Context c, int year, int month, int day) {
        prefs(c).edit().putInt(START, year * 10000 + month * 100 + day).apply();
    }

    static int days(Context c) { return prefs(c).getInt(DAYS, 30); }
    static void setDays(Context c, int n) { prefs(c).edit().putInt(DAYS, n == 29 ? 29 : 30).apply(); }
    static void toggleDays(Context c) { prefs(c).edit().putInt(DAYS, days(c) == 30 ? 29 : 30).apply(); }

    static int countdownMinutes(Context c) { return prefs(c).getInt(COUNTDOWN, 10); }
    static void setCountdownMinutes(Context c, int m) {
        prefs(c).edit().putInt(COUNTDOWN, Math.max(1, Math.min(30, m))).apply();
    }

    static final int SEHRI = 0, IFTAR = 1;
    private static final String[][] KEYS = {{SEHRI_FIRST, SEHRI_LAST}, {IFTAR_FIRST, IFTAR_LAST}};
    /** Placeholders (North India, Feb-Mar) until the masjid calendar's times are entered. */
    private static final int[][] DEFAULTS = {{5 * 60 + 35, 5 * 60 + 5}, {18 * 60 + 10, 18 * 60 + 28}};

    /** which: SEHRI or IFTAR; end: 0 first fast, 1 last fast. */
    static int endpoint(Context c, int which, int end) {
        return prefs(c).getInt(KEYS[which][end], DEFAULTS[which][end]);
    }

    static void setEndpoint(Context c, int which, int end, int minutes) {
        prefs(c).edit().putInt(KEYS[which][end], ((minutes % 1440) + 1440) % 1440).apply();
    }

    private static String dayKey(int which, int day) { return (which == SEHRI ? "sehri_d" : "iftar_d") + day; }

    /** Sehri or Iftar of fast `day` (0-based): the owner's correction for that day, else interpolated. */
    static int time(Context c, int which, int day) {
        int own = prefs(c).getInt(dayKey(which, day), -1);
        if (own >= 0) return own;
        int a = endpoint(c, which, 0), b = endpoint(c, which, 1), n = days(c);
        return n <= 1 ? a : Math.round(a + (b - a) * (float) day / (n - 1));
    }

    static void setDay(Context c, int which, int day, int minutes) {
        prefs(c).edit().putInt(dayKey(which, day), ((minutes % 1440) + 1440) % 1440).apply();
    }

    // ---- where are we ----

    /** 0-based fast of this calendar day, or -1 outside Ramadan. */
    static int fastOf(Context c, Calendar day) {
        Calendar d = (Calendar) day.clone();
        d.set(Calendar.HOUR_OF_DAY, 12);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        Calendar s = start(c);
        s.set(Calendar.HOUR_OF_DAY, 12);
        long diff = Math.round((d.getTimeInMillis() - s.getTimeInMillis()) / 86_400_000.0);
        return diff >= 0 && diff < days(c) ? (int) diff : -1;
    }

    static boolean isEid(Context c, Calendar day) {
        Calendar prev = (Calendar) day.clone();
        prev.add(Calendar.DAY_OF_MONTH, -1);
        return fastOf(c, day) < 0 && fastOf(c, prev) == days(c) - 1;
    }

    /** The Home / screensaver line during Ramadan and on Eid, else null (the namaz line shows instead). */
    static String line(Context c) {
        Calendar now = Calendar.getInstance();
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int fast = fastOf(c, now);
        if (fast >= 0) {
            int sehri = time(c, SEHRI, fast), iftar = time(c, IFTAR, fast);
            if (cur < sehri) {
                return "Sehri ends " + NamazTimes.format(sehri) + "  ·  " + NamazTimes.until(sehri - cur);
            }
            if (cur < iftar) {
                return "Roza " + (fast + 1) + "  ·  Iftar " + NamazTimes.format(iftar)
                        + "  ·  " + NamazTimes.until(iftar - cur);
            }
        }
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        int next = fastOf(c, tomorrow);
        if (next >= 0) {
            int sehri = time(c, SEHRI, next);
            return "Sehri ends " + NamazTimes.format(sehri) + "  ·  " + NamazTimes.until(1440 - cur + sehri);
        }
        if (isEid(c, now)) return "Eid Mubarak";
        return null;
    }

    static String startLabel(Context c) {
        return new SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).format(start(c).getTime());
    }
}
