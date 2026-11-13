package top.niunaijun.blackbox.fake.frameworks;

import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

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

            IAccountManagerResponse response = new IAccountManagerResponse.Stub() {
                @Override
                public void onResult(Bundle value) {
                    handleResult(userId, listener, value);
                }

                @Override
                public void onError(int errorCode, String errorMessage) {
                    Log.w(TAG, "Google sign-in from settings: error " + errorCode + ": " + errorMessage);
                    failed(listener, "Google sign-in failed ("
                            + (errorMessage != null ? errorMessage : "error " + errorCode) + ").");
                }
            };

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
            screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        BlackBoxCore.get().startActivity(screen, userId);
                        listener.onScreenOpened();
                    } catch (Throwable e) {
                        Log.e(TAG, "could not open Google's sign-in screen", e);
                        listener.onFailed("Could not open Google's sign-in screen: " + e);
                    }
                }
            });
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

    private static void failed(final Listener listener, final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.onFailed(message);
            }
        });
    }
}
