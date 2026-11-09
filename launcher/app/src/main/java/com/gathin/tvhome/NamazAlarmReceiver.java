package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Fires the namaz alert (NamazAlarm) and keeps the next one set across boots, clock changes and app updates.
 * When the TV is in standby nothing happens: the alert must not switch the TV on before Fajr.
 */
public class NamazAlarmReceiver extends BroadcastReceiver {

    static final String ACTION_ALERT = "com.gathin.tvhome.NAMAZ_ALERT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ALERT.equals(action)) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();
            if (screenOn && NamazAlarm.enabled(context)) {
                int prayer = intent.getIntExtra(NamazAlarm.EXTRA_PRAYER, 0);
                long jamaat = intent.getLongExtra(NamazAlarm.EXTRA_JAMAAT, 0);
                int kind = intent.getIntExtra(NamazAlarm.EXTRA_KIND, NamazAlarm.KIND_NAMAZ);
                if (!NamazAlarm.dueNow(context, jamaat, prayer, kind)) {
                    NamazAlarm.schedule(context);
                    return;
                }
                NamazAlarm.startCover(context, prayer, jamaat, kind);
                try {
                    context.startActivity(NamazAlarm.coverIntent(context).putExtra("chime", true));
                } catch (Exception ignored) {
                    // nothing else to show the cover with
                }
            }
        }
        NamazAlarm.schedule(context);   // always line up the next one
    }
}
