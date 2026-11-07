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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.graphics.drawable.Icon;
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
    private static final String KEY_SILENT_AUDIO = "silent_audio_enabled";
    private static final String KEY_AUDIO_STATE = "silent_audio_state";
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

    public static boolean isSilentAudioEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SILENT_AUDIO, false);
    }

    public static void setSilentAudioEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SILENT_AUDIO, enabled).apply();
    }

    /** A short description of what the silent audio is doing right now. */
    public static String silentAudioState(Context context) {
        return prefs(context).getString(KEY_AUDIO_STATE, "not started");
    }

    private void setAudioState(String state) {
        prefs(this).edit().putString(KEY_AUDIO_STATE, state).apply();
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
            stopSilentAudio();
            setAudioState("off");
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        startGuestLoop();
        applySilentAudio();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopGuestLoop();
        stopSilentAudio();
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
            ensureSilentAudio();
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

    // ------------------------------------------------------------ Silent audio
    //
    // Optional backup, off by default (it costs some battery). Many phones, Xiaomi HyperOS in
    // particular, leave apps alone while they play audio. To look like a real music player this
    // streams faint, inaudible noise (not pure zeros, which some phones skip as "no audio"),
    // publishes a MediaSession in the PLAYING state and shows a media-style notification, so the
    // system lists it in its media controls. It does not take audio focus, so other music keeps
    // playing normally.

    private static final int AUDIO_RATE = 44100;

    private MediaSession mediaSession;
    private AudioTrack silentTrack;
    private Thread audioThread;
    private volatile boolean audioRunning;

    private void applySilentAudio() {
        if (isSilentAudioEnabled(this)) {
            ensureSilentAudio();
        } else {
            stopSilentAudio();
            setAudioState("off");
            refreshNotification();
        }
    }

    /** Starts the audio if it is wanted and not running, and restarts it if it stalled. */
    private void ensureSilentAudio() {
        if (!isSilentAudioEnabled(this)) {
            return;
        }
        AudioTrack track = silentTrack;
        boolean healthy = track != null && audioRunning && audioThread != null && audioThread.isAlive();
        if (healthy) {
            try {
                healthy = track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
            } catch (Throwable t) {
                healthy = false;
            }
        }
        if (!healthy) {
            stopSilentAudio();
            startSilentAudio();
        }
    }

    private void startSilentAudio() {
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();
            int minBytes = AudioTrack.getMinBufferSize(
                    AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) {
                throw new IllegalStateException("no audio output available (code " + minBytes + ")");
            }
            final AudioTrack track = new AudioTrack(attributes, format, minBytes * 2,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                throw new IllegalStateException("the audio track did not initialize");
            }
            track.play();
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                track.release();
                throw new IllegalStateException("the audio track would not start");
            }
            silentTrack = track;
            audioRunning = true;
            audioThread = new Thread(() -> {
                short[] buffer = new short[AUDIO_RATE / 5 * 2]; // 200 ms of stereo
                int seed = 12345;
                try {
                    while (audioRunning) {
                        for (int i = 0; i < buffer.length; i++) {
                            seed = seed * 1103515245 + 12345;
                            // +2 or -2 out of 32768: about -84 dB, far below anything audible
                            buffer[i] = (short) (((seed >>> 16) & 1) == 0 ? 2 : -2);
                        }
                        if (track.write(buffer, 0, buffer.length) < 0) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // the track was released while writing; the watchdog restarts it if needed
                }
            }, "BgRunner-silent-audio");
            audioThread.setDaemon(true);
            audioThread.start();

            startMediaSession();
            setAudioState("playing (inaudible audio and media session active)");
            refreshNotification();
        } catch (Throwable t) {
            stopSilentAudio();
            setAudioState("failed: " + t.getMessage());
            refreshNotification();
        }
    }

    private void stopSilentAudio() {
        audioRunning = false;
        AudioTrack track = silentTrack;
        silentTrack = null;
        audioThread = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (Throwable ignored) {
                // already stopped
            }
            try {
                track.release();
            } catch (Throwable ignored) {
                // already released
            }
        }
        stopMediaSession();
    }

    private void startMediaSession() {
        if (mediaSession != null) {
            return;
        }
        try {
            MediaSession session = new MediaSession(this, "BgRunnerKeepAlive");
            try {
                session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            } catch (Throwable ignored) {
                // flags are deprecated on new versions
            }
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPause() {
                    turnOffSilentAudio();
                }

                @Override
                public void onStop() {
                    turnOffSilentAudio();
                }
            });
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "BG App Runner")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Keeping your virtual apps running")
                    .build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build());
            session.setActive(true);
            mediaSession = session;
        } catch (Throwable t) {
            mediaSession = null;
        }
    }

    private void stopMediaSession() {
        MediaSession session = mediaSession;
        mediaSession = null;
        if (session != null) {
            try {
                session.setActive(false);
            } catch (Throwable ignored) {
                // already inactive
            }
            try {
                session.release();
            } catch (Throwable ignored) {
                // already released
            }
        }
    }

    /** Called when the user taps pause or stop on the media player. */
    private void turnOffSilentAudio() {
        setSilentAudioEnabled(this, false);
        handler.post(() -> {
            stopSilentAudio();
            setAudioState("off");
            refreshNotification();
        });
    }

    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable ignored) {
            // the foreground notification is already showing
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

        boolean media = mediaSession != null && audioRunning;
        builder.setContentTitle(media ? "BG App Runner is keeping apps alive" : "Virtual apps are kept running")
                .setContentText(media ? "Silent audio mode is on. Tap to open." : "Tap to open. Keep-alive is on.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(open);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build());
        } else {
            builder.addAction(android.R.drawable.ic_media_pause, "Stop", stop);
        }
        if (media) {
            try {
                builder.setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0));
            } catch (Throwable ignored) {
                // fall back to a plain notification
            }
        }
        return builder.build();
    }
}
