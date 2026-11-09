package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * The family's own masjid jamaat times, set by hand on the TV (NamazActivity), not calculated:
 * the masjid's times differ from the online ones. Stored as minutes after midnight.
 */
final class NamazTimes {

    static final String[] KEYS = {"fajr", "zuhr", "asr", "maghrib", "isha", "jumuah"};
    static final String[] NAMES = {"Fajr", "Zuhr", "Asr", "Maghrib", "Isha", "Jumu'ah"};
    /** Starting values until the owner sets the masjid's times. */
    private static final int[] DEFAULTS = {5 * 60 + 30, 13 * 60 + 30, 16 * 60 + 30, 17 * 60 + 40, 19 * 60 + 30, 13 * 60 + 30};
    static final int JUMUAH = 5;
    private static final String PREFS = "namaz_times";
    private static final String SHOW = "show_on_screensaver";

    private NamazTimes() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int get(Context c, int i) {
        return prefs(c).getInt(KEYS[i], DEFAULTS[i]);
    }

    static void set(Context c, int i, int minutes) {
        prefs(c).edit().putInt(KEYS[i], ((minutes % 1440) + 1440) % 1440).apply();
    }

    static boolean showOnScreensaver(Context c) {
        return prefs(c).getBoolean(SHOW, true);
    }

    static void setShowOnScreensaver(Context c, boolean show) {
        prefs(c).edit().putBoolean(SHOW, show).apply();
    }

    /** "5:30 AM" */
    static String format(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        String ampm = h < 12 ? "AM" : "PM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + (m < 10 ? "0" : "") + m + " " + ampm;
    }

    /** The five prayers of a day; on Friday Jumu'ah takes Zuhr's place. */
    private static int[] order(Calendar day) {
        boolean friday = day.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        return new int[]{0, friday ? JUMUAH : 1, 2, 3, 4};
    }

    /** Minutes after a jamaat's time during which it still shows as "now". */
    static final int NOW_MINUTES = 15;

    /**
     * {prayer index, minutes until it} for the next jamaat from now (tomorrow's Fajr after Isha). A jamaat that
     * started less than NOW_MINUTES ago is still the one shown, with 0 minutes: it is happening now.
     */
    static int[] next(Context c, Calendar now) {
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int i : order(now)) {
            int t = get(c, i);
            if (cur >= t && cur - t < NOW_MINUTES) return new int[]{i, 0};
        }
        for (int i : order(now)) {
            int t = get(c, i);
            if (t > cur) return new int[]{i, t - cur};
        }
        return new int[]{0, 1440 - cur + get(c, 0)};
    }

    /** "in 35m", "in 7h 53m", "in 2h" */
    static String until(int minutes) {
        if (minutes <= 0) return "now";
        if (minutes < 60) return "in " + minutes + "m";
        int h = minutes / 60, m = minutes % 60;
        return "in " + h + "h" + (m > 0 ? " " + m + "m" : "");
    }

    /** One short line for the screensaver: "Asr 4:30 PM · in 35m" */
    static String nextLine(Context c) {
        String ramadan = RamadanTimes.line(c);   // Sehri / Iftar during Ramadan, "Eid Mubarak" on Eid
        if (ramadan != null) return ramadan;
        int[] n = next(c, Calendar.getInstance());
        return NAMES[n[0]] + " " + format(get(c, n[0])) + "  ·  " + until(n[1]);
    }
}
