package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Namaz Timings: the masjid's jamaat times, set with the remote. Up/Down picks a prayer, Left/Right moves
 * its time by a minute (hold to go faster). Saved at once; the screensaver shows the next one.
 */
public class NamazActivity extends Activity {

    private static final int ROW_SHOW = NamazTimes.KEYS.length;
    private static final int ROW_ALERT = ROW_SHOW + 1, ROW_BEFORE = ROW_SHOW + 2, ROW_COVER = ROW_SHOW + 3;
    // Ramadan section
    private static final int R_START = ROW_SHOW + 4, R_DAYS = R_START + 1, R_SEHRI1 = R_START + 2,
            R_SEHRI2 = R_START + 3, R_IFTAR1 = R_START + 4, R_IFTAR2 = R_START + 5, R_COUNT = R_START + 6,
            R_TODAY_S = R_START + 7, R_TODAY_I = R_START + 8, R_END = R_START + 9;
    private static final String[] R_NAMES = {"Ramadan starts", "Fasts", "Sehri ends, first roza",
            "Sehri ends, last roza", "Iftar, first roza", "Iftar, last roza", "Iftar countdown (lock)",
            "Today's Sehri", "Today's Iftar"};
    private final TextView[] ramVals = new TextView[R_END - R_START];
    private final View[] todayRows = new View[2];

    private final TextView[] timeViews = new TextView[NamazTimes.KEYS.length];
    private TextView showValue, nextView, alertValue, beforeValue, coverValue;
    private float d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(px(48), px(28), px(48), px(28));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF06231C, 0xFF0B0F14, 0xFF000000});
        page.setBackground(bg);

        TextView title = text("Namaz Timings", 34, 0xFFFFFFFF);
        title.setTypeface(UiFont.get(this, R.string.font_light));
        page.addView(title);
        TextView sub = text("Masjid jamaat times", 16, 0xB3FFFFFF);
        sub.setPadding(0, px(2), 0, px(6));
        page.addView(sub);

        nextView = text("", 17, 0xFF7FE0B5);
        nextView.setPadding(0, 0, 0, px(14));
        page.addView(nextView);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < NamazTimes.KEYS.length; i++) {
            timeViews[i] = text("", 26, 0xFFFFFFFF);
            list.addView(row(i, NamazTimes.NAMES[i], timeViews[i]));
        }
        showValue = text("", 22, 0xFFFFFFFF);
        list.addView(row(ROW_SHOW, "Show on screensaver", showValue));
        alertValue = text("", 22, 0xFFFFFFFF);
        list.addView(row(ROW_ALERT, "Alert (chime + lock screen)", alertValue));
        beforeValue = text("", 22, 0xFFFFFFFF);
        list.addView(row(ROW_BEFORE, "Alert before jamaat", beforeValue));
        coverValue = text("", 22, 0xFFFFFFFF);
        list.addView(row(ROW_COVER, "Lock screen for", coverValue));

        TextView section = text("Ramadan", 30, 0xFFF6D678);
        section.setPadding(0, px(22), 0, px(4));
        list.addView(section);
        for (int r = R_START; r < R_END; r++) {
            ramVals[r - R_START] = text("", 22, 0xFFFFFFFF);
            View v = row(r, R_NAMES[r - R_START], ramVals[r - R_START]);
            list.addView(v);
            if (r == R_TODAY_S) todayRows[0] = v;
            if (r == R_TODAY_I) todayRows[1] = v;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(620), 0, 1f);
        page.addView(scroll, sp);

        TextView help = text("▲▼ Select      ◀▶ Change time (hold = faster)      Back = Exit",
                14, 0x99FFFFFF);
        help.setPadding(0, px(12), 0, 0);
        page.addView(help);

        setContentView(page);
        refresh();
        list.getChildAt(0).requestFocus();
    }

    private View row(int index, String name, TextView value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(22), px(10), px(22), px(10));
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(px(14));
        normal.setColor(0x14FFFFFF);
        r.setBackground(normal);
        r.setOnFocusChangeListener((v, has) -> {
            normal.setColor(has ? 0xFFFFFFFF : 0x14FFFFFF);
            int c = has ? 0xFF0B0F14 : 0xFFFFFFFF;
            ((TextView) r.getChildAt(0)).setTextColor(c);
            value.setTextColor(c);
            r.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(120).start();
        });
        r.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (index >= R_START) {
                int d2 = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                if (d2 == 0 && !(ok && index == R_DAYS)) return false;
                int rep = e.getRepeatCount();
                int st = rep > 25 ? 15 : rep > 8 ? 5 : 1;
                int fast = RamadanTimes.fastOf(this, Calendar.getInstance());
                if (index == R_START) {
                    RamadanTimes.shiftStart(this, d2);
                } else if (index == R_DAYS) {
                    if (rep == 0) RamadanTimes.toggleDays(this);
                } else if (index == R_COUNT) {
                    RamadanTimes.setCountdownMinutes(this, RamadanTimes.countdownMinutes(this) + d2);
                } else if (index == R_TODAY_S || index == R_TODAY_I) {
                    int w = index == R_TODAY_S ? RamadanTimes.SEHRI : RamadanTimes.IFTAR;
                    if (fast >= 0) RamadanTimes.setDay(this, w, fast, RamadanTimes.time(this, w, fast) + d2 * st);
                } else {
                    int w = index <= R_SEHRI2 ? RamadanTimes.SEHRI : RamadanTimes.IFTAR;
                    int end = (index == R_SEHRI2 || index == R_IFTAR2) ? 1 : 0;
                    RamadanTimes.setEndpoint(this, w, end, RamadanTimes.endpoint(this, w, end) + d2 * st);
                }
                NamazAlarm.schedule(this);
                refresh();
                return true;
            }
            if (index == ROW_ALERT || index == ROW_BEFORE || index == ROW_COVER) {
                int d2 = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
                if (d2 == 0 && !ok) return false;
                if (index == ROW_ALERT) {
                    if (e.getRepeatCount() == 0) NamazAlarm.setEnabled(this, !NamazAlarm.enabled(this));
                } else if (d2 != 0 && index == ROW_BEFORE) {
                    NamazAlarm.setMinutesBefore(this, NamazAlarm.minutesBefore(this) + d2);
                } else if (d2 != 0) {
                    NamazAlarm.setCoverMinutes(this, NamazAlarm.coverMinutes(this) + d2);
                }
                refresh();
                return true;
            }
            if (index == ROW_SHOW) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (e.getRepeatCount() == 0) {
                        NamazTimes.setShowOnScreensaver(this, !NamazTimes.showOnScreensaver(this));
                        refresh();
                    }
                    return true;
                }
                return false;
            }
            int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (dir == 0) return false;
            int rep = e.getRepeatCount();
            int step = rep > 25 ? 15 : rep > 8 ? 5 : 1;
            NamazTimes.set(this, index, NamazTimes.get(this, index) + dir * step);
            NamazAlarm.schedule(this);
            refresh();
            return true;
        });

        TextView label = text(name, 22, 0xFFFFFFFF);
        label.setGravity(Gravity.START);
        r.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        value.setGravity(Gravity.END);
        r.addView(value);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(6), px(5), px(6), px(5));
        r.setLayoutParams(lp);
        return r;
    }

    private void refresh() {
        for (int i = 0; i < timeViews.length; i++) {
            timeViews[i].setText("◀  " + NamazTimes.format(NamazTimes.get(this, i)) + "  ▶");
        }
        showValue.setText(NamazTimes.showOnScreensaver(this) ? "On" : "Off");
        alertValue.setText(NamazAlarm.enabled(this) ? "On" : "Off");
        beforeValue.setText("◀  " + NamazAlarm.minutesBefore(this) + " min  ▶");
        coverValue.setText("◀  " + NamazAlarm.coverMinutes(this) + " min  ▶");
        String arrows = "◀  %s  ▶";
        int fast = RamadanTimes.fastOf(this, Calendar.getInstance());
        ramVals[0].setText(String.format(arrows, RamadanTimes.startLabel(this)));
        ramVals[1].setText(RamadanTimes.days(this) + "");
        ramVals[2].setText(String.format(arrows, NamazTimes.format(RamadanTimes.endpoint(this, RamadanTimes.SEHRI, 0))));
        ramVals[3].setText(String.format(arrows, NamazTimes.format(RamadanTimes.endpoint(this, RamadanTimes.SEHRI, 1))));
        ramVals[4].setText(String.format(arrows, NamazTimes.format(RamadanTimes.endpoint(this, RamadanTimes.IFTAR, 0))));
        ramVals[5].setText(String.format(arrows, NamazTimes.format(RamadanTimes.endpoint(this, RamadanTimes.IFTAR, 1))));
        ramVals[6].setText(String.format(arrows, RamadanTimes.countdownMinutes(this) + " min"));
        for (View v : todayRows) v.setVisibility(fast >= 0 ? View.VISIBLE : View.GONE);
        if (fast >= 0) {
            ramVals[7].setText(String.format(arrows, NamazTimes.format(RamadanTimes.time(this, RamadanTimes.SEHRI, fast))));
            ramVals[8].setText(String.format(arrows, NamazTimes.format(RamadanTimes.time(this, RamadanTimes.IFTAR, fast))));
        }
        int[] n = NamazTimes.next(this, Calendar.getInstance());
        nextView.setText((n[1] <= 0 ? "Now: " : "Next: ") + NamazTimes.NAMES[n[0]] + " "
                + NamazTimes.format(NamazTimes.get(this, n[0]))
                + (n[1] <= 0 ? "" : "  ·  " + NamazTimes.until(n[1])));
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

    private int px(float dp) {
        return Math.round(dp * d);
    }
}
