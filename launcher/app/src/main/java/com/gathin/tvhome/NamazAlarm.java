package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * The namaz alert: a soft chime and a full-screen cover (NamazCoverActivity) a few minutes before each jamaat,
 * so nothing else is played over the azan. One exact alarm at a time, always for the next prayer: set again
 * when Home starts, when a time is changed, after the TV boots or its clock changes, and after each alert.
 */
final class NamazAlarm {

    static final String EXTRA_PRAYER = "prayer";
    static final String EXTRA_JAMAAT = "jamaat_at";
    static final String EXTRA_KIND = "kind";
    /** What a cover is for: a jamaat, or Iftar in Ramadan (then `prayer` holds the 0-based fast). */
    static final int KIND_NAMAZ = 0, KIND_IFTAR = 1;

    private static final String PREFS = "namaz_times";
    private static final String ON = "alert_on";
    private static final String BEFORE = "alert_before_min";
    private static final String COVER = "cover_min";
    private static final String COVER_UNTIL = "cover_until";
    private static final String COVER_PRAYER = "cover_prayer";
    private static final String COVER_JAMAAT = "cover_jamaat";
    private static final String COVER_KIND = "cover_kind";

    private NamazAlarm() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean(ON, true); }
    static int minutesBefore(Context c) { return prefs(c).getInt(BEFORE, 5); }
    static int coverMinutes(Context c) { return prefs(c).getInt(COVER, 15); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(ON, on).apply();
        schedule(c);
    }

    static void setMinutesBefore(Context c, int m) {
        prefs(c).edit().putInt(BEFORE, Math.max(0, Math.min(60, m))).apply();
        schedule(c);
    }

    static void setCoverMinutes(Context c, int m) {
        prefs(c).edit().putInt(COVER, Math.max(1, Math.min(30, m))).apply();
    }

    private static long at(Calendar day, int minutes) {
        Calendar at = (Calendar) day.clone();
        at.set(Calendar.HOUR_OF_DAY, minutes / 60);
        at.set(Calendar.MINUTE, minutes % 60);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        return at.getTimeInMillis();
    }

    /**
     * {alert time, prayer (or fast), jamaat / Iftar time, kind} of the next alert, or null. In Ramadan the Iftar
     * countdown replaces Maghrib's alert, which would come right on top of it.
     */
    static long[] nextAlert(Context c, long now) {
        int before = minutesBefore(c);
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        long[] best = null;
        for (int d = 0; d < 2; d++) {
            int fast = RamadanTimes.fastOf(c, day);
            boolean friday = day.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
            int[] order = {0, friday ? NamazTimes.JUMUAH : 1, 2, 3, 4};
            for (int p : order) {
                if (p == 3 && fast >= 0) continue;
                long jamaat = at(day, NamazTimes.get(c, p));
                long alert = jamaat - before * 60_000L;
                if (alert > now + 1000 && (best == null || alert < best[0])) {
                    best = new long[]{alert, p, jamaat, KIND_NAMAZ};
                }
            }
            if (fast >= 0) {
                long iftar = at(day, RamadanTimes.time(c, RamadanTimes.IFTAR, fast));
                long alert = iftar - RamadanTimes.countdownMinutes(c) * 60_000L;
                if (alert > now + 1000 && (best == null || alert < best[0])) {
                    best = new long[]{alert, fast, iftar, KIND_IFTAR};
                }
            }
            if (best != null) return best;
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    private static PendingIntent pending(Context c, int prayer, long jamaat, int kind) {
        Intent i = new Intent(c, NamazAlarmReceiver.class)
                .setAction(NamazAlarmReceiver.ACTION_ALERT)
                .putExtra(EXTRA_PRAYER, prayer)
                .putExtra(EXTRA_JAMAAT, jamaat)
                .putExtra(EXTRA_KIND, kind);
        return PendingIntent.getBroadcast(c, 4711, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Replaces any pending alert with one for the next prayer (or none when alerts are off). */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // Look the pending alert up without touching it: getBroadcast with FLAG_UPDATE_CURRENT would overwrite
        // the extras of the alert already set (prayer 0, time 0), and an alert going off then showed a wrong cover.
        PendingIntent old = PendingIntent.getBroadcast(c, 4711,
                new Intent(c, NamazAlarmReceiver.class).setAction(NamazAlarmReceiver.ACTION_ALERT),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (old != null) am.cancel(old);
        if (!enabled(c)) return;
        long[] next = nextAlert(c, System.currentTimeMillis());
        if (next == null) return;
        PendingIntent pi = pending(c, (int) next[1], next[2], (int) next[3]);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next[0], pi);
        }
    }

    // ---- the cover's state, so Home and the cover itself can tell whether it should still be up ----

    static void startCover(Context c, int prayer, long jamaat, int kind) {
        long until = kind == KIND_IFTAR
                ? jamaat + RamadanTimes.AFTER_IFTAR_MIN * 60_000L   // through Iftar, for the dua
                : System.currentTimeMillis() + coverMinutes(c) * 60_000L;
        prefs(c).edit().putLong(COVER_UNTIL, until).putInt(COVER_PRAYER, prayer).putLong(COVER_JAMAAT, jamaat)
                .putInt(COVER_KIND, kind).apply();
    }

    static int coverKind(Context c) { return prefs(c).getInt(COVER_KIND, KIND_NAMAZ); }

    static long coverUntil(Context c) { return prefs(c).getLong(COVER_UNTIL, 0); }
    static int coverPrayer(Context c) { return prefs(c).getInt(COVER_PRAYER, 0); }
    static long coverJamaat(Context c) { return prefs(c).getLong(COVER_JAMAAT, 0); }

    static boolean coverActive(Context c) {
        long now = System.currentTimeMillis(), jamaat = coverJamaat(c);
        int kind = coverKind(c);
        long lead = (kind == KIND_IFTAR ? RamadanTimes.countdownMinutes(c) : minutesBefore(c)) * 60_000L;
        long after = (kind == KIND_IFTAR ? RamadanTimes.AFTER_IFTAR_MIN : coverMinutes(c)) * 60_000L;
        // Only around its own jamaat: a cover left over from a clock jump (a morning alert at night) is ignored.
        return now < coverUntil(c) && now >= jamaat - lead - 120_000L && now <= jamaat + after
                && matchesSchedule(c, jamaat, coverPrayer(c), kind);
    }

    /** Whether `jamaat` is exactly the set time of that prayer (or of that fast's Iftar) on its day. */
    static boolean matchesSchedule(Context c, long jamaat, int prayer, int kind) {
        if (jamaat <= 0) return false;
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(jamaat);
        if (at.get(Calendar.SECOND) != 0 || at.get(Calendar.MILLISECOND) != 0) return false;
        int minutes = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE);
        if (kind == KIND_IFTAR) {
            return RamadanTimes.fastOf(c, at) == prayer
                    && RamadanTimes.time(c, RamadanTimes.IFTAR, prayer) == minutes;
        }
        return prayer >= 0 && prayer < NamazTimes.KEYS.length && NamazTimes.get(c, prayer) == minutes;
    }

    /**
     * Whether an alert that just went off is real: its jamaat (or Iftar) is set and due within the alert's own
     * window. Anything else (a stale or replayed alarm) only sets the next alert and shows nothing.
     */
    static boolean dueNow(Context c, long jamaat, int prayer, int kind) {
        if (!matchesSchedule(c, jamaat, prayer, kind)) return false;
        long now = System.currentTimeMillis();
        long lead = (kind == KIND_IFTAR ? RamadanTimes.countdownMinutes(c) : minutesBefore(c)) * 60_000L;
        return now >= jamaat - lead - 120_000L && now <= jamaat + 60_000L;
    }

    static void clearCover(Context c) {
        prefs(c).edit().putLong(COVER_UNTIL, 0).apply();
    }

    static Intent coverIntent(Context c) {
        return new Intent(c, NamazCoverActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }
}
