package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Extra dim: a black, see-through, untouchable layer over the whole screen, for when the TV is still too bright
 * at backlight 0. Set from the Brightness row (Quick Settings), kept across reboots. Needs SYSTEM_ALERT_WINDOW
 * (appops set ... SYSTEM_ALERT_WINDOW allow over adb).
 */
public class DimService extends Service {

    private static final String PREFS = "namaz_times";
    private static final String DIM = "dim_percent";
    static final int MAX_DIM = 80;
    private static final String CHANNEL = "dim";

    private View layer;

    static int level(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(DIM, 0);
    }

    static void setLevel(Context c, int percent) {
        int p = Math.max(0, Math.min(MAX_DIM, percent));
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(DIM, p).apply();
        ensure(c);
    }

    /** Starts, updates or stops the layer to match the saved level. */
    static void ensure(Context c) {
        Intent i = new Intent(c, DimService.class);
        try {
            if (level(c) > 0 && Settings.canDrawOverlays(c)) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } else {
                c.stopService(i);
            }
        } catch (Exception ignored) {
            // not allowed right now (background start); Home tries again when it comes up
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen dim",
                        NotificationManager.IMPORTANCE_MIN));
            }
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_brightness)
                    .setContentTitle("Screen dim")
                    .build();
            startForeground(4712, n);
        }
        int dim = level(this);
        if (dim <= 0 || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (layer == null) {
            layer = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            try {
                wm.addView(layer, lp);
            } catch (Exception e) {
                layer = null;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        layer.setBackgroundColor(Color.argb(Math.round(dim * 2.55f), 0, 0, 0));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (layer != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(layer);
            } catch (Exception ignored) {
                // already gone
            }
            layer = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
