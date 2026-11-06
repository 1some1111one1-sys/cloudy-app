package top.niunaijun.blackboxa.runner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * A foreground service that runs INSIDE one virtual-app process (":p0", ":p1", ...). Each
 * subclass is declared in the manifest with the matching android:process, so the process hosting
 * a virtual app gets real foreground-service priority instead of relying on the main process.
 */
public class GuestKeepService extends Service {

    private static final String CHANNEL_ID = "bg_runner_guest";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String name = getClass().getSimpleName();
            int index = 0;
            try {
                index = Integer.parseInt(name.substring(1));
            } catch (Throwable ignored) {
                // keep 0
            }
            startForeground(4300 + index, buildNotification(name));
        } catch (Throwable ignored) {
            // never crash the process that hosts a virtual app
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String processName) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Virtual app running", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle("A virtual app is running")
                .setContentText("Kept alive in process " + processName.toLowerCase())
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    public static class P0 extends GuestKeepService {
    }

    public static class P1 extends GuestKeepService {
    }

    public static class P2 extends GuestKeepService {
    }

    public static class P3 extends GuestKeepService {
    }

    public static class P4 extends GuestKeepService {
    }

    public static class P5 extends GuestKeepService {
    }

    public static class P6 extends GuestKeepService {
    }

    public static class P7 extends GuestKeepService {
    }

    public static class P8 extends GuestKeepService {
    }

    public static class P9 extends GuestKeepService {
    }

    public static class P10 extends GuestKeepService {
    }

    public static class P11 extends GuestKeepService {
    }

    public static class P12 extends GuestKeepService {
    }

    public static class P13 extends GuestKeepService {
    }

    public static class P14 extends GuestKeepService {
    }

    public static class P15 extends GuestKeepService {
    }

    public static class P16 extends GuestKeepService {
    }

    public static class P17 extends GuestKeepService {
    }

    public static class P18 extends GuestKeepService {
    }

    public static class P19 extends GuestKeepService {
    }

    public static class P20 extends GuestKeepService {
    }

    public static class P21 extends GuestKeepService {
    }

    public static class P22 extends GuestKeepService {
    }

    public static class P23 extends GuestKeepService {
    }

    public static class P24 extends GuestKeepService {
    }

    public static class P25 extends GuestKeepService {
    }

    public static class P26 extends GuestKeepService {
    }

    public static class P27 extends GuestKeepService {
    }

    public static class P28 extends GuestKeepService {
    }

    public static class P29 extends GuestKeepService {
    }

    public static class P30 extends GuestKeepService {
    }

    public static class P31 extends GuestKeepService {
    }

    public static class P32 extends GuestKeepService {
    }

    public static class P33 extends GuestKeepService {
    }

    public static class P34 extends GuestKeepService {
    }

    public static class P35 extends GuestKeepService {
    }

    public static class P36 extends GuestKeepService {
    }

    public static class P37 extends GuestKeepService {
    }

    public static class P38 extends GuestKeepService {
    }

    public static class P39 extends GuestKeepService {
    }

    public static class P40 extends GuestKeepService {
    }

    public static class P41 extends GuestKeepService {
    }

    public static class P42 extends GuestKeepService {
    }

    public static class P43 extends GuestKeepService {
    }

    public static class P44 extends GuestKeepService {
    }

    public static class P45 extends GuestKeepService {
    }

    public static class P46 extends GuestKeepService {
    }

    public static class P47 extends GuestKeepService {
    }

    public static class P48 extends GuestKeepService {
    }

    public static class P49 extends GuestKeepService {
    }
}
