#!/usr/bin/env python3
"""Apply the BG App Runner overlay onto a checkout of the NewBlackbox engine.

Usage: apply_overlay.py <path-to-engine-checkout>

It copies overlay/app/** into the engine's app module and patches the app manifest:
  - adds the permissions the keep-alive service needs
  - makes RunnerActivity the launcher (WelcomeActivity stays reachable from it)
  - registers KeepAliveService
It fails loudly if an expected anchor is missing, so a changed upstream is noticed early.
"""
import shutil
import sys
from pathlib import Path

PERMISSIONS = [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.WAKE_LOCK",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SYSTEM_ALERT_WINDOW",
]

LAUNCHER_CATEGORY = '<category android:name="android.intent.category.LAUNCHER" />'

COMPONENTS = """
        <activity
            android:name="top.niunaijun.blackboxa.runner.RunnerActivity"
            android:label="BG App Runner"
            android:exported="true"
            android:theme="@style/RunnerTheme">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name="top.niunaijun.blackboxa.runner.AuthRedirectActivity"
            android:label="Continue in BG App Runner"
            android:exported="true"
            android:excludeFromRecents="true"
            android:launchMode="singleTask"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="ms-xal-0000000048183522" />
            </intent-filter>
        </activity>

        <activity
            android:name="top.niunaijun.blackboxa.runner.OpenWithActivity"
            android:label="Open in BG App Runner"
            android:exported="true"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="*/*" />
            </intent-filter>
        </activity>

        <provider
            android:name="top.niunaijun.blackboxa.runner.OpenWithProvider"
            android:authorities="${applicationId}.openwith"
            android:exported="false" />

        <service
            android:name="top.niunaijun.blackboxa.runner.KeepAliveService"
            android:exported="false" />

        <service
            android:name="top.niunaijun.blackboxa.runner.FloatingButtonService"
            android:exported="false" />
"""

# One keep-alive service per virtual-app stub process (the engine ships ":p0" to ":p49").
COMPONENTS += "".join(
    f"""
        <service
            android:name="top.niunaijun.blackboxa.runner.GuestKeepService$P{i}"
            android:exported="false"
            android:process=":p{i}" />
"""
    for i in range(50)
)


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------------------------------------
# Engine source patches
#
# Fix: apps crashed on launch when BG App Runner runs in a secondary Android user (for example
# Xiaomi "Second space", work profiles, or user 10). The engine told guest apps that their uid was
# just an app id such as 10231, which Android reads as user 0. Every user-aware system call made
# for the guest (isUserUnlocked, queryIntentActivities, ...) then targeted user 0 and was refused
# with "requires INTERACT_ACROSS_USERS". Guests now see the real host user plus their own app id.
# On the primary user (0) the value is identical to before, so nothing changes there.
# ---------------------------------------------------------------------------------------------

ENGINE_ROOT = Path("Bcore/src/main/java/top/niunaijun/blackbox")

VISIBLE_UID_HELPERS = """    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }

    /**
     * The uid a guest app sees from Os.getuid(): the real host user plus the guest's app id.
     * On the primary user this equals the app id. In a secondary user (for example user 10) it
     * keeps Android's user-aware calls on that user instead of user 0.
     */
    public static int getVisibleUid() {
        return BlackBoxCore.getHostUserId() * BUserHandle.PER_USER_RANGE + getBAppId();
    }

    /** True for the uid returned by getVisibleUid() when the host is not on the primary user. */
    public static boolean isVisibleUid(int uid) {
        return BlackBoxCore.getHostUserId() != 0 && uid == getVisibleUid();
    }
"""

# (file, old text, new text, expected number of occurrences of old text)
ENGINE_PATCHES = [
    (
        "app/BActivityThread.java",
        """    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }
""",
        VISIBLE_UID_HELPERS,
        1,
    ),
    (
        "fake/service/libcore/OsStub.java",
        "            return BActivityThread.getBAppId();",
        "            return BActivityThread.getVisibleUid();",
        1,
    ),
    (
        "fake/service/base/UidMethodProxy.java",
        "        if (uid == BActivityThread.getBUid()) {",
        "        if (uid == BActivityThread.getBUid() || BActivityThread.isVisibleUid(uid)) {",
        1,
    ),
    (
        "utils/MethodParameterUtils.java",
        "if (uid == BlackBoxCore.getBUid()) {",
        "if (uid == BlackBoxCore.getBUid() || top.niunaijun.blackbox.app.BActivityThread.isVisibleUid(uid)) {",
        2,
    ),
    (
        "fake/service/IPackageManagerProxy.java",
        "            if (uid == BlackBoxCore.getHostUid()) {\n                args[0] = BActivityThread.getBUid();",
        "            if (uid == BlackBoxCore.getHostUid() || BActivityThread.isVisibleUid(uid)) {\n                args[0] = BActivityThread.getBUid();",
        1,
    ),
]


# Second set of patches: keep-alive support for virtual-app processes.
#  - handleCreateService used to swallow every service created inside a virtual-app process. The
#    host's own GuestKeepService (bound with BIND_IMPORTANT) must really be created there.
#  - StayActive drops pause/stop messages while the BG button flag is set.
KEEP_PATCH_MARKER = "StayActive.holdIfNeeded"
KEEP_PATCHES = [
    (
        # Android 13 and newer: the engine's new-intent delivery only knows Android 12 and older
        # entry points, so a running virtual app never received the file or sign-in link.
        "app/BActivityThread.java",
        """            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }""",
        """            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            } else {
                top.niunaijun.blackbox.fake.service.NewIntentCompat.deliver(mainThread, token, newIntent);
            }""",
        1,
    ),
    (
        # Leave a trace in the debug log of what is delivered to a running virtual app.
        "core/system/am/ActivityStack.java",
        """            activityRecord.processRecord.bActivityThread.handleNewIntent(activityRecord.token, intent);""",
        """            Log.d(TAG, "OpenWith: new intent for running " + activityRecord.component + " ("
                    + top.niunaijun.blackbox.fake.service.NewIntentCompat.describe(intent) + ")");
            activityRecord.processRecord.bActivityThread.handleNewIntent(activityRecord.token, intent);""",
        1,
    ),
    (
        # ... and of what a freshly started virtual app is launched with.
        "fake/service/HCallbackProxy.java",
        """        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {""",
        """        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            Slog.d(TAG, "OpenWith: launching " + activityInfo.name + " ("
                    + top.niunaijun.blackbox.fake.service.NewIntentCompat.describe(stubRecord.mTarget) + ")");
            if (BActivityThread.getAppConfig() == null) {""",
        1,
    ),
    (
        # The engine ships with a built-in "send logs" feature that uploads the whole log of this
        # app (and crash reports, automatically) to a server run by the engine's author. Nobody
        # asked for that and logs can hold sign-in links, so it is switched off: with no chat id
        # configured the upload code returns before doing anything.
        "app/configuration/ClientConfiguration.java",
        'return "-1003719573856";',
        'return ""; // log uploads to a third-party server are switched off',
        1,
    ),
    (
        # The engine replaces Android's crash handler inside every virtual app with one that only
        # writes a file, so the error that killed an app never reaches the log. Print it as well
        # (tag BlackBoxCrash), so "Copy debug log" and any logcat capture show what went wrong.
        "utils/CrashMonitor.java",
        """    public static void handleCrash(String crashType, Thread thread, Throwable throwable) {
        try {
            sTotalCrashes.incrementAndGet();""",
        """    public static void handleCrash(String crashType, Thread thread, Throwable throwable) {
        try {
            android.util.Log.e("BlackBoxCrash", crashType + " in thread "
                    + (thread != null ? thread.getName() : "?") + ": " + throwable, throwable);
            sTotalCrashes.incrementAndGet();""",
        1,
    ),
    (
        # Instagram closed about ten seconds after it started: it asks Android's Credential Manager
        # for saved logins, Android refuses the call from inside the container with a SecurityException
        # and Instagram does not handle that. ICredentialManagerProxy answers "no saved logins" instead.
        "fake/hook/HookManager.java",
        """                addInjector(new ISensitiveContentProtectionManagerProxy());
""",
        """                addInjector(new ISensitiveContentProtectionManagerProxy());
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    addInjector(new top.niunaijun.blackbox.fake.service.ICredentialManagerProxy());
                }
""",
        1,
    ),
    (
        # Google sign-in from any space except space 0 died at once. The engine's account service
        # keeps one record per space but never writes the space number into it, so every record
        # said "space 0". Sign-in then looked for Google's sign-in service in space 0, found nothing
        # there (Play Store and Google services live in the space the user chose), fell back to a
        # plain Android bind that Android refuses, and the exception killed the app that asked.
        # Records saved by older builds carry the wrong number too, so it is set on every lookup.
        "core/system/accounts/BAccountManagerService.java",
        """                mUserAccountsMap.put(userId, bUserAccounts);
            }
            return mUserAccountsMap.get(userId);""",
        """                mUserAccountsMap.put(userId, bUserAccounts);
            }
            bUserAccounts.userId = userId;
            return bUserAccounts;""",
        1,
    ),
    (
        # Installing any app made the account service forget every other app's sign-in service
        # (it rebuilt the list from the new app alone), so adding Play Store after Google services
        # removed Google sign-in until the next restart. Rebuild the whole list instead.
        "core/system/accounts/BAccountManagerService.java",
        """    public void onPackageInstalled(String packageName, int userId) {
        loadAuthenticatorCache(packageName);""",
        """    public void onPackageInstalled(String packageName, int userId) {
        loadAuthenticatorCache(null);""",
        1,
    ),
    (
        # Say in the log which sign-in service is used for which space, and when there is none.
        "core/system/accounts/BAccountManagerService.java",
        """            AuthenticatorInfo authenticatorInfo = mAuthenticatorCache.authenticators.get(authenticatorType);
""",
        """            AuthenticatorInfo authenticatorInfo = mAuthenticatorCache.authenticators.get(authenticatorType);
            if (authenticatorInfo == null) {
                Log.w("BlackBoxAccounts", "no sign-in service is installed for account type " + authenticatorType
                        + " (space " + mAccounts.userId + ")");
            } else {
                Log.i("BlackBoxAccounts", "sign-in service for " + authenticatorType + ": "
                        + authenticatorInfo.serviceInfo.packageName + "/" + authenticatorInfo.serviceInfo.name
                        + " in space " + mAccounts.userId);
            }
""",
        1,
    ),
    (
        # If Android still refuses to connect to the sign-in service (for example Google services
        # are installed in another space), tell the app that sign-in failed instead of letting the
        # exception travel back and close it.
        "core/system/accounts/BAccountManagerService.java",
        """            if (!mContext.bindService(intent, this, flags)) {""",
        """            boolean bound = false;
            try {
                bound = mContext.bindService(intent, this, flags);
            } catch (SecurityException e) {
                Log.e("BlackBoxAccounts", "cannot connect to " + componentName.flattenToShortString()
                        + " for space " + mAccounts.userId
                        + " (is Google Play services installed in this space?): " + e.getMessage());
            }
            if (!bound) {""",
        1,
    ),
    (
        # GTA San Andreas closed right after its loading bar: it writes its first file into its own
        # folder on "phone storage" (Android/data/<package>/files), the folder did not exist, the
        # game does not check that and crashed (CColAccel::endCache). On a real phone Android creates
        # these folders; inside the container nobody did, and File.mkdirs() could not build them
        # through the path rewrite either. Create the usual folders before the rewrite is switched on.
        "core/IOCore.java",
        """                File external = BEnvironment.getExternalUserDir(BlackBoxCore.getUserId());
""",
        """                File external = BEnvironment.getExternalUserDir(BlackBoxCore.getUserId());
                top.niunaijun.blackbox.core.env.ExternalFolders.prepare(external, packageName);
""",
        1,
    ),
    (
        # The engine answered Android's "create this folder on phone storage" request (StorageManager
        # .mkdirs, which getExternalFilesDir() falls back to) with a plain "ok" and created nothing.
        # Build the folder in the container's copy of the storage instead.
        "fake/service/IStorageManagerProxy.java",
        """    @ProxyMethod("mkdirs")
    public static class mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;""",
        """    @ProxyMethod("mkdirs")
    public static class mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[args.length - 1] instanceof String) {
                    String real = top.niunaijun.blackbox.core.IOCore.get()
                            .redirectPath((String) args[args.length - 1]);
                    if (real != null && !real.isEmpty()) {
                        top.niunaijun.blackbox.utils.FileUtils.mkdirs(real);
                    }
                }
            } catch (Throwable ignored) {
                // the call itself must never fail
            }
            return 0;""",
        1,
    ),
    (
        # Say in the log what the sign-in service of a space answers, step by step (tag
        # BlackBoxAccounts), so a failed Google sign-in shows where it stopped. Only the names of the
        # entries are logged, never their values.
        "core/system/accounts/BAccountManagerService.java",
        """        public void onServiceConnected(ComponentName name, IBinder service) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);""",
        """        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("BlackBoxAccounts", "connected to the sign-in service " + name + " (space " + mAccounts.userId + ")");
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """        public void onServiceDisconnected(ComponentName name) {
            mAuthenticator = null;""",
        """        public void onServiceDisconnected(ComponentName name) {
            Log.w("BlackBoxAccounts", "the sign-in service " + name + " disconnected (space " + mAccounts.userId + ")");
            mAuthenticator = null;""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """                mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                        options);""",
        """                Log.i("BlackBoxAccounts", "asking the sign-in service to add a " + mAccountType
                        + " account (space " + mAccounts.userId + ")");
                mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                        options);""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """        public void onResult(Bundle result) {
            mNumResults++;
            Intent intent = null;""",
        """        public void onResult(Bundle result) {
            Log.i("BlackBoxAccounts", "the sign-in service answered with "
                    + (result == null ? "nothing" : String.valueOf(result.keySet()))
                    + " (space " + mAccounts.userId + ")");
            mNumResults++;
            Intent intent = null;""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """                ResolveInfo resolveInfo = mPms.resolveActivity(intent, 0, null, mAccounts.userId);
                if (resolveInfo == null) {
                    return false;
                }""",
        """                ResolveInfo resolveInfo = mPms.resolveActivity(intent, 0, null, mAccounts.userId);
                if (resolveInfo == null) {
                    Log.w("BlackBoxAccounts", "the sign-in screen " + intent.getComponent() + " / "
                            + intent.getAction() + " is not installed in space " + mAccounts.userId);
                    return false;
                }""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """        public void onError(int errorCode, String errorMessage) {
            mNumErrors++;""",
        """        public void onError(int errorCode, String errorMessage) {
            Log.w("BlackBoxAccounts", "sign-in error " + errorCode + ": " + errorMessage
                    + " (space " + mAccounts.userId + ")");
            mNumErrors++;""",
        1,
    ),
    (
        # An account nobody set a per-app visibility for counted as "hidden from every app", so the
        # calls that filter by visibility (getAccountsByFeatures, getAccountByTypeAndFeatures, the
        # "for package" lookups that Google services use) returned no accounts at all, even right
        # after a successful sign-in. Android treats such an account as visible; so does this.
        "core/system/accounts/BAccountManagerService.java",
        """        if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
            return visibility;
        }
        return AccountManager.VISIBILITY_NOT_VISIBLE;""",
        """        if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
            return visibility;
        }
        return AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """                Integer visibility = account.visibility.get(packageName);
                if (visibility != null && visibility == AccountManager.VISIBILITY_VISIBLE) {""",
        """                Integer visibility = account.visibility.get(packageName);
                if (visibility == null || visibility == AccountManager.VISIBILITY_VISIBLE
                        || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE) {""",
        1,
    ),
    (
        "core/system/accounts/BAccountManagerService.java",
        """                    Integer visibility = account.visibility.get(packageName);
                    if (visibility != null && visibility == AccountManager.VISIBILITY_VISIBLE) {""",
        """                    Integer visibility = account.visibility.get(packageName);
                    if (visibility == null || visibility == AccountManager.VISIBILITY_VISIBLE
                            || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE) {""",
        1,
    ),
    (
        # When the app's process is not running yet, the engine starts it and re-queues the launch
        # with a plain "launcher" intent, which throws away what the caller asked for (a file to
        # open, a sign-in return). Keep the original intent instead.
        "fake/service/HCallbackProxy.java",
        "ProxyActivityRecord.saveStub(intent, launchIntentForPackage, stubRecord.mActivityInfo,",
        "ProxyActivityRecord.saveStub(intent, stubRecord.mTarget != null ? stubRecord.mTarget : launchIntentForPackage, stubRecord.mActivityInfo,",
        1,
    ),
    (
        "fake/service/HCallbackProxy.java",
        """                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);""",
        """                if (BuildCompat.isPie() && msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()
                        && top.niunaijun.blackbox.fake.service.StayActive.holdIfNeeded(msg.obj)) {
                    return true;
                }
                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);""",
        1,
    ),
    (
        "fake/service/HCallbackProxy.java",
        """            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))""",
        """            if (serviceInfo.name.startsWith("top.niunaijun.blackboxa.runner.")) {
                return false; // the host's own keep-alive service: let Android create it normally
            }
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))""",
        1,
    ),
    (
        "fake/service/ActivityManagerCommonProxy.java",
        """                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));
            }

            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(""",
        """                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));
            }

            // Web links go to the phone's own browser, not to a virtual app's https filter.
            if (top.niunaijun.blackbox.fake.service.ExternalLinks.isWebLink(intent)) {
                top.niunaijun.blackbox.fake.service.ExternalLinks.protectForTrip();
                return method.invoke(who, args);
            }

            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(""",
        1,
    ),
    (
        "fake/service/IActivityManagerProxy.java",
        """            return method.invoke(who, args);
        }

        int getIntentIndex(Object[] args) {""",
        """            // Apps may not broadcast to "all users" (the copied Google services do, and crashed).
            if (args.length > 0 && args[args.length - 1] instanceof Integer
                    && ((Integer) args[args.length - 1]) == -1) {
                args[args.length - 1] = BlackBoxCore.getHostUserId();
            }
            return method.invoke(who, args);
        }

        int getIntentIndex(Object[] args) {""",
        1,
    ),
    (
        "core/system/am/ActivityStack.java",
        """        return startActivityInSourceTask(intent,
                resolvedType, resultTo, resultWho, requestCode, flags, options, userId, topActivityRecord, activityInfo, launchModeFlags);
    }
""",
        """        if (intent.getBooleanExtra("bg_runner_from_host", false)) {
            // Started by BG App Runner itself (Open with, sign-in return): launch from the host
            // instead of through the running app, which Android blocks while that app is hidden.
            return startActivityFromHost(intent, activityInfo, userId, launchModeFlags);
        }
        return startActivityInSourceTask(intent,
                resolvedType, resultTo, resultWho, requestCode, flags, options, userId, topActivityRecord, activityInfo, launchModeFlags);
    }

    private int startActivityFromHost(Intent intent, ActivityInfo activityInfo, int userId, int launchMode) {
        ActivityRecord record = newActivityRecord(intent, activityInfo, null, userId);
        Intent shadow = startActivityProcess(userId, intent, activityInfo, record);
        shadow.setAction(UUID.randomUUID().toString());
        shadow.addFlags(launchMode);
        shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        BlackBoxCore.getContext().startActivity(shadow);
        return 0;
    }
""",
        1,
    ),
    (
        "fake/service/IPackageManagerProxy.java",
        """            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;""",
        """            if (AppSystemEnv.isOpenPackage(packageName)
                    || top.niunaijun.blackbox.fake.service.ExternalLinks.isBrowserPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;""",
        2,
    ),
    (
        "fake/service/IActivityManagerProxy.java",
        """                if (ProxyManifest.isProxy((String) auth)) {
                    return method.invoke(who, args);
                }

                if (BuildCompat.isQ()) {
                    args[1] = BlackBoxCore.getHostPkg();""",
        """                if (ProxyManifest.isProxy((String) auth)) {
                    return method.invoke(who, args);
                }

                // BG App Runner's own provider for files handed to container apps ("Open with").
                // The app gets the real provider. The engine's wrapper (ContentProviderDelegate)
                // replaces every text argument of every call with the host's package name, which
                // also overwrites the read/write mode ("r"), so every file open failed.
                if (((String) auth).equals(BlackBoxCore.getHostPkg() + ".openwith")) {
                    if (args.length > 1 && args[1] instanceof String) {
                        args[1] = BlackBoxCore.getHostPkg();
                    }
                    return method.invoke(who, args);
                }

                if (BuildCompat.isQ()) {
                    args[1] = BlackBoxCore.getHostPkg();""",
        1,
    ),
    (
        "fake/service/IAudioServiceProxy.java",
        """    @ProxyMethod("requestAudioFocus")""",
        """    // Lets StayActive mute this virtual app's own players while it is in the background.
    @ProxyMethod("trackPlayer")
    public static class TrackPlayer extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(who, args);
            top.niunaijun.blackbox.fake.service.StayActive.onTrackPlayer(args);
            return result;
        }
    }

    @ProxyMethod("requestAudioFocus")""",
        1,
    ),
    (
        # Sign-in return: the screen that opened the browser is still waiting for the answer.
        # Look for it first, whatever its launch mode or task says, and hand the answer to it.
        "core/system/am/ActivityStack.java",
        """        Log.d(TAG, "startActivityLocked : " + resolveInfo.activityInfo);
        ActivityInfo activityInfo = resolveInfo.activityInfo;
""",
        """        Log.d(TAG, "startActivityLocked : " + resolveInfo.activityInfo);
        ActivityInfo activityInfo = resolveInfo.activityInfo;
        if (intent.getBooleanExtra("bg_runner_auth_return", false)
                && deliverToWaitingActivity(userId, activityInfo, intent)) {
            return 0;
        }
""",
        1,
    ),
    (
        "core/system/am/ActivityStack.java",
        """    private void deliverNewIntentLocked(ActivityRecord activityRecord, Intent intent) {
""",
        """    /**
     * The browser sent the user back after signing in. The screen that opened the browser is still
     * waiting for the answer, so the answer goes to that running screen, whatever its launch mode
     * says, and only then is its task brought back (the order Android uses). Returns false when no
     * such screen is running, so the normal start-up path takes over.
     */
    private boolean deliverToWaitingActivity(int userId, ActivityInfo activityInfo, Intent intent) {
        try {
            ComponentName name = ComponentUtils.toComponentName(activityInfo);
            ActivityRecord waiting = null;
            synchronized (mTasks) {
                for (TaskRecord task : mTasks.values()) {
                    if (task.userId != userId) {
                        continue;
                    }
                    for (ActivityRecord activity : task.activities) {
                        if (!activity.finished && activity.token != null && name.equals(activity.component)
                                && activity.processRecord != null
                                && activity.processRecord.bActivityThread != null
                                && activity.processRecord.bActivityThread.asBinder().isBinderAlive()) {
                            waiting = activity;
                        }
                    }
                }
            }
            if (waiting == null) {
                Log.d(TAG, "OpenWith: no running " + name.flattenToShortString()
                        + " is waiting for the sign-in result, starting it instead");
                return false;
            }
            Intent answer = new Intent(intent);
            answer.removeExtra("bg_runner_from_host");
            answer.removeExtra("bg_runner_auth_return");
            // the browser's own intent names no app and no screen
            answer.setComponent(null);
            answer.setPackage(null);
            Log.d(TAG, "OpenWith: the sign-in result goes to the running " + name.flattenToShortString());
            deliverNewIntentLocked(waiting, answer);
            try {
                if (waiting.task != null) {
                    mAms.moveTaskToFront(waiting.task.id, 0);
                }
            } catch (Throwable t) {
                // the answer is already delivered, so it must not be started a second time
                Log.w(TAG, "OpenWith: could not bring the app to the front", t);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "OpenWith: could not hand the sign-in result to the running screen", t);
            return false;
        }
    }

    private void deliverNewIntentLocked(ActivityRecord activityRecord, Intent intent) {
""",
        1,
    ),
    (
        # Bring the task to the front only AFTER the app has been given its new intent, as Android
        # does. The engine did it first, so a screen waiting for a browser sign-in came back
        # without an answer, which it treats as "cancelled".
        "core/system/am/ActivityStack.java",
        """        mAms.moveTaskToFront(taskRecord.id, 0);
""",
        """        final int bgFrontTaskId = taskRecord.id; // shown after the new intent is delivered, see below
""",
        1,
    ),
    (
        "core/system/am/ActivityStack.java",
        """        if (startTaskToFront)
            return 0;
""",
        """        if (startTaskToFront) {
            mAms.moveTaskToFront(bgFrontTaskId, 0);
            return 0;
        }
""",
        1,
    ),
    (
        "core/system/am/ActivityStack.java",
        """            deliverNewIntentLocked(newIntentRecord, intent);
            return 0;
        } else if (ignore) {
            return 0;
        }
""",
        """            deliverNewIntentLocked(newIntentRecord, intent);
            mAms.moveTaskToFront(newIntentRecord.task != null ? newIntentRecord.task.id : bgFrontTaskId, 0);
            return 0;
        } else if (ignore) {
            mAms.moveTaskToFront(bgFrontTaskId, 0);
            return 0;
        }
        mAms.moveTaskToFront(bgFrontTaskId, 0);
""",
        1,
    ),
]


BGMUTE_MODULE = """

# --- BG App Runner: native audio mute for virtual apps (added by scripts/apply_overlay.py)
include $(CLEAR_VARS)
LOCAL_MODULE := bgmute
LOCAL_SRC_FILES := BgMute/BgMute.cpp
LOCAL_C_INCLUDES += $(LOCAL_PATH)
LOCAL_CPPFLAGS += -std=c++17 -fno-rtti -w
LOCAL_LDFLAGS += -Wl,--gc-sections,-z,max-page-size=16384
LOCAL_STATIC_LIBRARIES := libdobby xdl
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
"""


def patch_native_module(engine: Path) -> None:
    mk = engine / "Bcore/src/main/cpp/Android.mk"
    if not mk.is_file():
        fail(f"{mk} not found; the engine layout changed")
    text = mk.read_text(encoding="utf-8")
    if "LOCAL_MODULE := bgmute" in text:
        print("native bgmute module already registered, skipping")
        return
    if not (engine / "Bcore/src/main/cpp/BgMute/BgMute.cpp").is_file():
        fail("overlay/Bcore/src/main/cpp/BgMute/BgMute.cpp was not copied into the engine")
    mk.write_text(text.rstrip("\n") + "\n" + BGMUTE_MODULE, encoding="utf-8")
    print("registered the bgmute native module")


def patch_keep_alive(engine: Path) -> None:
    root = engine / ENGINE_ROOT
    marker = root / "fake/service/HCallbackProxy.java"
    if not marker.is_file():
        fail(f"{marker} not found; the engine layout changed")
    if KEEP_PATCH_MARKER in marker.read_text(encoding="utf-8"):
        print("keep-alive engine patch already applied, skipping")
        return
    texts = {}
    for rel, old, new, count in KEEP_PATCHES:
        file = root / rel
        if not file.is_file():
            fail(f"{file} not found; the engine layout changed")
        text = texts.setdefault(rel, file.read_text(encoding="utf-8"))
        found = text.count(old)
        if found != count:
            fail(f"{rel}: expected {count} match(es) of the keep-alive patch anchor, found {found}; "
                 "the engine changed, so pin an older engine_ref or update scripts/apply_overlay.py")
    for rel, old, new, count in KEEP_PATCHES:
        texts[rel] = texts[rel].replace(old, new)
    for rel, text in texts.items():
        (root / rel).write_text(text, encoding="utf-8")
    print(f"patched {len(texts)} engine files for keep-alive support")


def patch_engine(engine: Path) -> None:
    root = engine / ENGINE_ROOT
    marker_file = root / "app/BActivityThread.java"
    if not marker_file.is_file():
        fail(f"{marker_file} not found; the engine layout changed")
    if "getVisibleUid" in marker_file.read_text(encoding="utf-8"):
        print("engine already patched, skipping")
        return

    # Check every anchor first so a changed engine fails before anything is half-patched.
    texts = {}
    for rel, old, new, count in ENGINE_PATCHES:
        file = root / rel
        if not file.is_file():
            fail(f"{file} not found; the engine layout changed")
        text = texts.setdefault(rel, file.read_text(encoding="utf-8"))
        found = text.count(old)
        if found != count:
            fail(f"{rel}: expected {count} match(es) of the patch anchor, found {found}; "
                 "the engine changed, so pin an older engine_ref or update scripts/apply_overlay.py")
    for rel, old, new, count in ENGINE_PATCHES:
        texts[rel] = texts[rel].replace(old, new)
    for rel, text in texts.items():
        (root / rel).write_text(text, encoding="utf-8")
    print(f"patched {len(texts)} engine files for secondary-user support")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply_overlay.py <engine-checkout>")

    engine = Path(sys.argv[1]).resolve()
    repo = Path(__file__).resolve().parent.parent
    overlay_app = repo / "overlay" / "app"
    app = engine / "app"
    manifest = app / "src" / "main" / "AndroidManifest.xml"

    if not manifest.is_file():
        fail(f"{manifest} not found; is {engine} a NewBlackbox checkout?")

    shutil.copytree(overlay_app, app, dirs_exist_ok=True)
    overlay_core = repo / "overlay" / "Bcore"
    if overlay_core.is_dir():
        shutil.copytree(overlay_core, engine / "Bcore", dirs_exist_ok=True)
    patch_engine(engine)
    patch_keep_alive(engine)
    patch_native_module(engine)

    text = manifest.read_text(encoding="utf-8")
    if "runner.RunnerActivity" in text:
        print("manifest already patched, nothing to do")
        return

    if "<application" not in text or "</application>" not in text:
        fail("manifest has no <application> element")
    if text.count(LAUNCHER_CATEGORY) != 1:
        fail(f"expected exactly one LAUNCHER category, found {text.count(LAUNCHER_CATEGORY)}")

    missing = [p for p in PERMISSIONS if f'android:name="{p}"' not in text]
    block = "".join(f'<uses-permission android:name="{p}" />\n    ' for p in missing)
    text = text.replace("<application", block + "<application", 1)

    # The engine's own welcome screen stops being a launcher; RunnerActivity opens it.
    text = text.replace(LAUNCHER_CATEGORY, "", 1)

    text = text.replace("</application>", COMPONENTS + "    </application>", 1)
    manifest.write_text(text, encoding="utf-8")
    print(f"patched {manifest}")


if __name__ == "__main__":
    main()
