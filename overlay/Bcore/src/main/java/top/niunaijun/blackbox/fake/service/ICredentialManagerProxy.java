package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Android's Credential Manager (the "Sign in with Google" / passkey picker, Android 14+) cannot work
 * for an app that lives inside the container: the system checks the caller against the real app
 * (this app), not the app inside. Instagram asks it for saved logins a few seconds after it starts,
 * Android answers with a SecurityException, and Instagram does not expect that, so it closes.
 *
 * Inside the container the calls are answered with the ordinary "no saved logins" error that apps
 * already handle, so they fall back to their normal login screen.
 */
public class ICredentialManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ICredentialManager";
    private static final String SERVICE = "credential";

    private static final String NO_CREDENTIAL =
            "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL";
    private static final String NO_CREATE_OPTIONS =
            "android.credentials.CreateCredentialException.TYPE_NO_CREATE_OPTIONS";
    private static final String MESSAGE = "Credential Manager is not available inside the container";

    public ICredentialManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService(SERVICE);
            if (binder == null) {
                return null;
            }
            Class<?> stub = Class.forName("android.credentials.ICredentialManager$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, binder);
        } catch (Exception e) {
            Slog.d(TAG, "getWho error: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRServiceManager.get().getService(SERVICE) != null) {
            replaceSystemService(SERVICE);
            Slog.d(TAG, "Hooked the Credential Manager");
        } else {
            Slog.d(TAG, "No Credential Manager on this Android version");
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /** Calls {@code methodName} on the callback argument that implements {@code interfaceName}. */
    private static void answer(Object[] args, String interfaceName, String methodName,
                               Class<?>[] types, Object[] values) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            Class<?> callback = findInterface(arg.getClass(), interfaceName);
            if (callback == null) {
                continue;
            }
            try {
                callback.getMethod(methodName, types).invoke(arg, values);
            } catch (Throwable e) {
                Slog.w(TAG, "could not answer " + interfaceName + "." + methodName + ": " + e);
            }
            return;
        }
        Slog.w(TAG, "no " + interfaceName + " among the arguments");
    }

    private static Class<?> findInterface(Class<?> type, String simpleName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().endsWith("." + simpleName)) {
                    return i;
                }
            }
        }
        return null;
    }

    private static void error(Object[] args, String interfaceName, String type) {
        Slog.d(TAG, "answering " + interfaceName + " with " + type);
        answer(args, interfaceName, "onError", new Class<?>[]{String.class, String.class},
                new Object[]{type, MESSAGE});
    }

    @ProxyMethod("executeGetCredential")
    public static class ExecuteGetCredential extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            error(args, "IGetCredentialCallback", NO_CREDENTIAL);
            return null;
        }
    }

    @ProxyMethod("executePrepareGetCredential")
    public static class ExecutePrepareGetCredential extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            error(args, "IPrepareGetCredentialCallback", NO_CREDENTIAL);
            return null;
        }
    }

    @ProxyMethod("executeCreateCredential")
    public static class ExecuteCreateCredential extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            error(args, "ICreateCredentialCallback", NO_CREATE_OPTIONS);
            return null;
        }
    }

    @ProxyMethod("clearCredentialState")
    public static class ClearCredentialState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            answer(args, "IClearCredentialStateCallback", "onSuccess", new Class<?>[0], new Object[0]);
            return null;
        }
    }
}
