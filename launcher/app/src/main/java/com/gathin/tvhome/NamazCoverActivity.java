package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen namaz cover: shown a few minutes before a jamaat (NamazAlarm) for cover_min minutes, with a soft
 * chime. While it is up nothing else can be opened: every key but volume is swallowed, and if anything takes the
 * screen anyway (Home, an app button on the remote) the cover comes straight back until its time is over.
 */
public class NamazCoverActivity extends Activity {

    /** The cover a chime was last played for, so coming back to the front does not ring again. */
    private static long chimedFor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView countdown, footer, title;
    /** Iftar in Ramadan (NamazAlarm.KIND_IFTAR): a countdown to Iftar, then "Iftar Time" with a chime. */
    private boolean iftar;
    private String iftarSub;
    private MediaPlayer player;
    private boolean leaving;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long until = NamazAlarm.coverUntil(NamazCoverActivity.this);
            if (now >= until) {
                leaving = true;
                finish();
                return;
            }
            long toJamaat = NamazAlarm.coverJamaat(NamazCoverActivity.this) - now;
            if (iftar) {
                title.setText(toJamaat > 0 ? "Iftar in " + mmss(toJamaat) : "Iftar Time");
                if (toJamaat <= 0 && chimedFor != until) {   // the chime rings at Iftar itself
                    chimedFor = until;
                    playChime();
                }
            } else {
                countdown.setText(toJamaat > 0 ? "in " + mmss(toJamaat) : "Jamaat time");
            }
            footer.setText("TV unlocks in " + mmss(until - now));
            handler.postDelayed(this, 1000 - (now % 1000) + 20);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!NamazAlarm.coverActive(this)) {
            leaving = true;
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        float d = getResources().getDisplayMetrics().density;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF06352A, 0xFF071A16, 0xFF000000}));

        int prayer = NamazAlarm.coverPrayer(this);
        iftar = NamazAlarm.coverKind(this) == NamazAlarm.KIND_IFTAR;
        if (iftar) {
            buildIftar(page, prayer, d);
            return;
        }
        int jamaatMin = NamazTimes.get(this, prayer);

        TextView name = text(NamazTimes.NAMES[prayer] + " Namaz Time", 72, 0xFFFFFFFF);
        name.setTypeface(UiFont.get(this, R.string.font_medium));
        page.addView(name);

        TextView pray = text("Please go to pray", 44, 0xFFF6D678);
        pray.setPadding(0, (int) (10 * d), 0, 0);
        page.addView(pray);

        TextView when = text("Jamaat " + NamazTimes.format(jamaatMin), 24, 0xD9FFFFFF);
        when.setPadding(0, (int) (36 * d), 0, 0);
        page.addView(when);

        countdown = text("", 24, 0xD9FFFFFF);
        countdown.setPadding(0, (int) (4 * d), 0, 0);
        page.addView(countdown);

        footer = text("", 16, 0x80FFFFFF);
        footer.setPadding(0, (int) (48 * d), 0, 0);
        page.addView(footer);

        show(page);
        long until = NamazAlarm.coverUntil(this);
        if (chimedFor != until) {
            chimedFor = until;
            playChime();
        }
    }

    private void show(View page) {
        setContentView(page);
        page.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Iftar: "Iftar in 9:59", the fast and its time, and the dua for breaking the fast. */
    private void buildIftar(LinearLayout page, int fast, float d) {
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B1036, 0xFF0B1A2E, 0xFF000000}));
        title = text("", 76, 0xFFFFFFFF);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        page.addView(title);

        iftarSub = "Roza " + (fast + 1) + "  ·  Iftar "
                + NamazTimes.format(RamadanTimes.time(this, RamadanTimes.IFTAR, fast));
        TextView sub = text(iftarSub, 30, 0xFFF6D678);
        sub.setPadding(0, (int) (6 * d), 0, (int) (34 * d));
        page.addView(sub);

        TextView arabic = text("اللَّهُمَّ إِنِّي "
                + "لَكَ صُمْتُ وَبِكَ "
                + "آمَنْتُ وَعَلَى "
                + "رِزْقِكَ أَفْطَرْتُ",
                34, 0xFFFFFFFF);
        arabic.setTypeface(android.graphics.Typeface.DEFAULT);
        page.addView(arabic);

        TextView translit = text("Allahumma inni laka sumtu wa bika aamantu wa 'ala rizqika aftartu", 22, 0xD9FFFFFF);
        translit.setPadding(0, (int) (14 * d), 0, 0);
        page.addView(translit);
        TextView meaning = text("O Allah, I fasted for You, I believe in You, and I break my fast with Your provision.",
                18, 0x99FFFFFF);
        meaning.setPadding(0, (int) (6 * d), 0, 0);
        page.addView(meaning);

        countdown = new TextView(this);   // unused in this layout; the tick writes the title instead
        footer = text("", 16, 0x80FFFFFF);
        footer.setPadding(0, (int) (40 * d), 0, 0);
        page.addView(footer);
        show(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    /** Something else took the screen while the cover should still be up: bring it back. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!leaving && NamazAlarm.coverActive(this)) {
            Context app = getApplicationContext();
            handler.postDelayed(() -> {
                if (NamazAlarm.coverActive(app)) {
                    try {
                        app.startActivity(NamazAlarm.coverIntent(app));
                    } catch (Exception ignored) {
                        // nothing to bring it back with
                    }
                }
            }, 400);
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** Volume (and power) still work; everything else is swallowed while the cover is up. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                return super.dispatchKeyEvent(event);
            default:
                return true;
        }
    }

    @Override
    public void onBackPressed() {
        // stays up until its time is over
    }

    /** Times the chime rings, with a short pause between. */
    private static final int CHIMES = 3;
    private static final long CHIME_GAP_MS = 900;
    private int chimesLeft;

    private void playChime() {
        try {
            if (player != null) player.release();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, android.net.Uri.parse(
                    "android.resource://" + getPackageName() + "/" + R.raw.namaz_chime));
            chimesLeft = CHIMES - 1;
            player.setOnCompletionListener(mp -> {
                if (chimesLeft-- > 0) {
                    handler.postDelayed(() -> {
                        if (player == mp) {
                            mp.seekTo(0);
                            mp.start();
                        }
                    }, CHIME_GAP_MS);
                } else {
                    mp.release();
                    if (player == mp) player = null;
                }
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            if (player != null) {
                player.release();
                player = null;
            }
        }
    }

    private static String mmss(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setTypeface(UiFont.get(this, R.string.font_regular));
        return t;
    }
}
