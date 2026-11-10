package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.view.KeyEvent;

/**
 * Apple TV style navigation sounds: a soft tick when the remote moves the focus, a deeper one on OK.
 * On by default; switched in the Quick Settings panel. One SoundPool for the whole app, loaded once.
 */
final class ClickSound {

    private static final String PREFS = "namaz_times";
    private static final String ON = "click_sound";
    private static SoundPool pool;
    private static int tick, select;

    private ClickSound() {}

    static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON, on).apply();
    }

    /** Loads the sounds ahead of the first key press (SoundPool decodes asynchronously; a cold play is silent). */
    static void preload(Context c) {
        if (enabled(c)) load(c);
    }

    private static void load(Context c) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        Context app = c.getApplicationContext();
        tick = pool.load(app, R.raw.nav_tick, 1);
        select = pool.load(app, R.raw.nav_select, 1);
    }

    /** Call from dispatchKeyEvent: plays for the first press of a D-pad or OK key (not for held keys). */
    static void onKey(Context c, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        int id;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                id = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                id = 1;
                break;
            default:
                return;
        }
        if (!enabled(c)) return;
        load(c);
        pool.play(id == 0 ? tick : select, 0.6f, 0.6f, 1, 0, 1f);
    }
}
