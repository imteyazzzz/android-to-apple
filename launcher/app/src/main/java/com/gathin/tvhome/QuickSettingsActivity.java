package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_moon, R.string.namaz_timings, 0, new Intent(this, NamazActivity.class));
        addBrightnessRow(rows);
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        // Many TVs (MediaTek Android TV 9) answer no Display page at all; leave the row out instead of a dead one.
        if (getPackageManager().resolveActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS), 0) != null) {
            addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        }
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        rows.getChildAt(0).requestFocus();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }

    /** MediaTek TVs keep the panel backlight in Settings.Global and their TV service applies every change. */
    private static final String BACKLIGHT = "picture_backlight";
    private static final int BACKLIGHT_STEP = 5;

    /**
     * Brightness: Left/Right moves the TV's backlight in steps of 5 (0-100), live. Only on TVs that have the
     * picture_backlight setting; writing it needs WRITE_SECURE_SETTINGS (pm grant over adb).
     */
    private void addBrightnessRow(LinearLayout rows) {
        if (Settings.Global.getString(getContentResolver(), BACKLIGHT) == null) return;
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_brightness);
        ((TextView) row.findViewById(R.id.title)).setText(R.string.quick_brightness);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView value = row.findViewById(R.id.status);
        value.setTextSize(15);
        Runnable show = () -> value.setText("◀  "
                + Settings.Global.getInt(getContentResolver(), BACKLIGHT, 50) + "  ▶");
        show.run();
        row.setOnKeyListener((v, keyCode, e) -> {
            int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (dir == 0) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int cur = Settings.Global.getInt(getContentResolver(), BACKLIGHT, 50);
                int next = Math.max(0, Math.min(100, (cur / BACKLIGHT_STEP + dir) * BACKLIGHT_STEP));
                try {
                    Settings.Global.putInt(getContentResolver(), BACKLIGHT, next);
                } catch (SecurityException ex) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                show.run();
            }
            return true;
        });
        rows.addView(row);
    }
}
