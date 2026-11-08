package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Catches the address a browser sends the user back to after signing in (for example Minecraft's
 * "ms-xal-...://auth?code=..."). Only this app is registered with Android for such addresses, not
 * the apps inside the container, so this screen hands the address to the container app that
 * declares it.
 */
public class AuthRedirectActivity extends Activity {
    private static final String TAG = "AuthRedirect";
    private static final int USER_ID = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        forward(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        forward(intent);
        finish();
    }

    private void forward(Intent original) {
        try {
            if (original == null || original.getData() == null) {
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, original.getData());
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            ResolveInfo info = BlackBoxCore.getBPackageManager().resolveActivity(
                    intent, PackageManager.GET_META_DATA, null, USER_ID);
            if (info == null || info.activityInfo == null) {
                Log.w(TAG, "no container app handles " + original.getData().getScheme());
                Toast.makeText(this, "No app in the container handles this sign-in link.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            ActivityInfo activity = info.activityInfo;
            intent.setComponent(new ComponentName(activity.packageName, activity.name));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.d(TAG, "forwarding " + original.getData().getScheme() + " to " + activity.packageName
                    + "/" + activity.name);
            BlackBoxCore.get().startActivity(intent, USER_ID);
        } catch (Throwable t) {
            Log.w(TAG, "could not forward the sign-in link", t);
            Toast.makeText(this, "Could not return to the app: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
