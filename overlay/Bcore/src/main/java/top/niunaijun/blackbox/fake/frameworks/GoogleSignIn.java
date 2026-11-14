package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.GmsCore;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.accounts.IBAccountManagerService;

/**
 * Starts "add a Google account" for one space of the container, the same request Android's own
 * Settings sends. It asks the container's account service to talk to Google Play services of that
 * space, opens the sign-in screen Google returns inside the container, and reports the result.
 *
 * Every callback of {@link Listener} runs on the main thread.
 */
public final class GoogleSignIn {
    private static final String TAG = "BlackBoxAccounts";
    private static final String ACCOUNT_TYPE = "com.google";
    /** How long Google Play services get to give a first answer before the user is told. */
    private static final long FIRST_ANSWER_MS = 30_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        /** Google's sign-in screen was opened inside the container. */
        void onScreenOpened();

        /** Google finished adding the account. */
        void onSignedIn(String accountName);

        /** Sign-in could not be started, or Google refused it. */
        void onFailed(String message);
    }

    private GoogleSignIn() {
    }

    public static void start(final int userId, final Listener listener) {
        if (!BlackBoxCore.get().isInstalled(GmsCore.GMS_PKG, userId)) {
            failed(listener, "Google Play services are not turned on for this space yet.");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                request(userId, listener);
            }
        }, "BlackBoxGoogleSignIn").start();
    }

    private static void request(final int userId, final Listener listener) {
        try {
            IBinder binder = BlackBoxCore.get().getService(ServiceManager.ACCOUNT_MANAGER);
            if (binder == null) {
                failed(listener, "The container's account service is not running. "
                        + "Open any app in the container once, then try again.");
                return;
            }
            IBAccountManagerService service = IBAccountManagerService.Stub.asInterface(binder);

            Bundle options = new Bundle();
            options.putString("androidPackageName", BlackBoxCore.getHostPkg());

            final AtomicBoolean answered = new AtomicBoolean(false);
            IAccountManagerResponse response = new IAccountManagerResponse.Stub() {
                @Override
                public void onResult(Bundle value) {
                    answered.set(true);
                    handleResult(userId, listener, value);
                }

                @Override
                public void onError(int errorCode, String errorMessage) {
                    answered.set(true);
                    Log.w(TAG, "Google sign-in from settings: error " + errorCode + ": " + errorMessage);
                    failed(listener, "Google sign-in failed ("
                            + (errorMessage != null ? errorMessage : "error " + errorCode) + ").");
                }
            };

            // Nothing times the account service out, so without this a stuck Google Play services
            // would leave the person waiting with no message at all.
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (answered.compareAndSet(false, true)) {
                        Log.w(TAG, "Google sign-in from settings: no answer after "
                                + (FIRST_ANSWER_MS / 1000) + " s (space " + userId + ")");
                        listener.onFailed("Google Play services did not answer within "
                                + (FIRST_ANSWER_MS / 1000) + " seconds. Try again; if it keeps "
                                + "happening, tap Copy debug log and send it.");
                    }
                }
            }, FIRST_ANSWER_MS);

            Log.i(TAG, "Google sign-in from settings, space " + userId);
            service.addAccount(response, ACCOUNT_TYPE, null, null, true, options, userId);
        } catch (Throwable e) {
            Log.e(TAG, "Google sign-in from settings could not start", e);
            failed(listener, "Google sign-in could not start: " + e);
        }
    }

    private static void handleResult(final int userId, final Listener listener, Bundle value) {
        if (value == null) {
            failed(listener, "Google sign-in ended without an answer.");
            return;
        }
        // Only the names of the entries are logged, never their values.
        Log.i(TAG, "Google sign-in from settings, answer holds " + value.keySet());

        final Intent screen = value.getParcelable(AccountManager.KEY_INTENT);
        if (screen != null) {
            openScreen(userId, listener, screen);
            return;
        }

        final String account = value.getString(AccountManager.KEY_ACCOUNT_NAME);
        if (account != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onSignedIn(account);
                }
            });
            return;
        }

        String message = value.getString(AccountManager.KEY_ERROR_MESSAGE);
        failed(listener, "Google sign-in did not finish"
                + (message != null ? " (" + message + ")" : "") + ".");
    }

    /**
     * Opens the sign-in screen Google returned. This goes straight to the container's activity
     * manager: the splash screen {@code BlackBoxCore.startActivity} normally goes through closes
     * without launching anything when the intent names no package, which is how Google names its
     * screen. Runs on the thread the answer arrived on, never on the main thread.
     */
    private static void openScreen(final int userId, final Listener listener, Intent screen) {
        final String target = screen.getComponent() != null
                ? screen.getComponent().flattenToShortString()
                : String.valueOf(screen.getAction());
        try {
            ResolveInfo found = BlackBoxCore.getBPackageManager().resolveActivity(screen, 0, null, userId);
            if (found == null || found.activityInfo == null) {
                Log.w(TAG, "Google's sign-in screen " + target + " is not installed in space " + userId);
                failed(listener, "Google's sign-in screen (" + target
                        + ") is not installed in this space. Turn Google Play services off and on "
                        + "again, then retry.");
                return;
            }
            Log.i(TAG, "opening Google's sign-in screen " + target + " in space " + userId);
            screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Start it from this app (which is on screen) rather than through a running container app.
            screen.putExtra("bg_runner_from_host", true);
            BlackBoxCore.getBActivityManager().startActivity(screen, userId);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onScreenOpened();
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "could not open Google's sign-in screen " + target, e);
            failed(listener, "Could not open Google's sign-in screen: " + e);
        }
    }

    private static void failed(final Listener listener, final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.onFailed(message);
            }
        });
    }
}
