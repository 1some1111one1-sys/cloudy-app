package top.niunaijun.blackboxa.runner;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foreground service that keeps the host app (and the virtual apps it runs) at a higher
 * process priority, and holds a partial wake lock so the CPU keeps running with the screen off.
 */
public class KeepAliveService extends Service {

    private static final String PREFS = "bg_runner_prefs";
    private static final String KEY_ENABLED = "keep_alive_enabled";
    private static final String CHANNEL_ID = "bg_runner_keepalive";
    private static final int NOTIFICATION_ID = 4242;
    private static final String ACTION_STOP = "top.niunaijun.blackboxa.runner.STOP";

    private PowerManager.WakeLock wakeLock;

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void start(Context context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).apply();
        Intent intent = new Intent(context, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply();
        context.stopService(new Intent(context, KeepAliveService.class));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs(this).edit().putBoolean(KEY_ENABLED, false).apply();
            stopGuestLoop();
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        startGuestLoop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGuestLoop();
        releaseWakeLock();
        super.onDestroy();
    }

    // ------------------------------------------------------------ Virtual app processes
    //
    // Virtual apps run in separate stub processes (":p0", ":p1", ...). A foreground service in
    // the main process does not protect them, so for every running stub process this service
    // starts GuestKeepService, a foreground service that lives inside that same process.

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> guestServices = new HashSet<>();
    private final Runnable guestLoop = new Runnable() {
        @Override
        public void run() {
            keepGuestsAlive();
            handler.postDelayed(this, 4000);
        }
    };

    private Intent guestIntent(int index) {
        return new Intent().setComponent(new ComponentName(
                getPackageName(), "top.niunaijun.blackboxa.runner.GuestKeepService$P" + index));
    }

    private void startGuestLoop() {
        handler.removeCallbacks(guestLoop);
        handler.post(guestLoop);
    }

    private void stopGuestLoop() {
        handler.removeCallbacks(guestLoop);
        for (Integer index : new HashSet<>(guestServices)) {
            try {
                stopService(guestIntent(index));
            } catch (Throwable ignored) {
                // process already gone
            }
        }
        guestServices.clear();
    }

    private void keepGuestsAlive() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes == null) {
                return;
            }
            Pattern pattern = Pattern.compile("^" + Pattern.quote(getPackageName()) + ":p(\\d+)$");
            Set<Integer> running = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                Matcher m = pattern.matcher(p.processName);
                if (m.matches()) {
                    running.add(Integer.parseInt(m.group(1)));
                }
            }
            guestServices.retainAll(running);
            for (Integer index : running) {
                if (guestServices.contains(index)) {
                    continue;
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(guestIntent(index));
                    } else {
                        startService(guestIntent(index));
                    }
                    guestServices.add(index);
                } catch (Throwable ignored) {
                    // try again on the next round
                }
            }
        } catch (Throwable ignored) {
            // never let the keep-alive loop crash the service
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BgRunner:KeepAlive");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Background keep-alive", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps virtual apps running in the background");
            nm.createNotificationChannel(channel);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, RunnerActivity.class), piFlags);

        Intent stopIntent = new Intent(this, KeepAliveService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle("Virtual apps are kept running")
                .setContentText("Tap to open. Keep-alive is on.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(open);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        } else {
            builder.addAction(0, "Stop", stop);
        }
        return builder.build();
    }
}
