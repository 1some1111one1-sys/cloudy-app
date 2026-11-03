package com.cloudy.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.cloudy.app.core.Account;
import com.cloudy.app.core.CloudClients;
import com.cloudy.app.core.GoogleDriveClient;
import com.cloudy.app.core.GoogleOAuth;
import com.cloudy.app.core.LoopbackReceiver;
import com.cloudy.app.core.MegaClient;
import com.cloudy.app.core.TeraBoxClient;
import java.io.IOException;

/**
 * Connects a drive. Same sign-in methods as the CloudMemory app:
 * Google Drive (system browser + loopback redirect), MEGA (e-mail, password, optional two-factor code) and TeraBox
 * (the real TeraBox website in a web view; only its session cookie is kept), plus plain WebDAV.
 */
public final class AddDriveActivity extends Activity {
    private static final int REQUEST_TERABOX = 1;
    private static final String[] TYPES = {"Google Drive", "MEGA", "TeraBox", "WebDAV"};

    private Spinner type;
    private EditText name;
    // Google
    private LinearLayout googleBox;
    private EditText googleId, googleSecret;
    private TextView googleStatus;
    private Button googleSignIn;
    // MEGA
    private LinearLayout megaBox;
    private EditText megaEmail, megaPassword, megaCode, megaKey;
    // TeraBox
    private LinearLayout teraBox;
    private EditText teraSite;
    private TextView teraStatus;
    // WebDAV
    private LinearLayout davBox;
    private EditText davUrl, davUser, davPass;

    private TextView status;
    private Button save;

    private String googleToken = "";
    private String googleEmail = "";
    private String teraCookie = "";
    private LoopbackReceiver receiver;
    private boolean builtInGoogle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        builtInGoogle = !BuildConfig.GOOGLE_CLIENT_ID.isEmpty() && !BuildConfig.GOOGLE_CLIENT_SECRET.isEmpty();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        page.setPadding(pad, pad, pad, pad);

        page.addView(Ui.label(this, "Which drive do you want to use?"));
        type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPES));
        page.addView(type);

        name = Ui.field(this, page, "Name (optional)", false);

        // ---- Google Drive
        googleBox = Ui.box(this);
        if (!builtInGoogle) {
            googleBox.addView(Ui.note(this, "Google needs a client ID from your own Google Cloud project (a one-time, "
                    + "5-minute setup, see the README). Paste it here, then sign in."));
            googleId = Ui.field(this, googleBox, "Client ID", false);
            googleSecret = Ui.field(this, googleBox, "Client secret", true);
        }
        googleSignIn = Ui.button(this, googleBox, "Sign in with Google", v -> startGoogleSignIn());
        googleStatus = Ui.note(this, "");
        googleBox.addView(googleStatus);
        page.addView(googleBox);

        // ---- MEGA
        megaBox = Ui.box(this);
        megaEmail = Ui.field(this, megaBox, "MEGA e-mail", false);
        megaEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        megaPassword = Ui.field(this, megaBox, "MEGA password", true);
        megaCode = Ui.field(this, megaBox, "Two-factor code (only if you use one)", false);
        megaKey = Ui.field(this, megaBox, "MEGA app key (optional)", false);
        megaBox.addView(Ui.note(this, "Your files are decrypted on this phone; the password only goes to MEGA."));
        page.addView(megaBox);

        // ---- TeraBox
        teraBox = Ui.box(this);
        teraBox.addView(Ui.note(this, "You sign in on TeraBox's own website inside the app. Cloudy never sees your "
                + "password; it keeps only the session cookie the site sets (stored encrypted). Unofficial and "
                + "experimental: TeraBox can change its site at any time."));
        teraSite = Ui.field(this, teraBox, "TeraBox address", false);
        teraSite.setText(TeraBoxClient.DEFAULT_SITE);
        Ui.button(this, teraBox, "Sign in on the TeraBox website", v -> startTeraBoxSignIn());
        teraStatus = Ui.note(this, "");
        teraBox.addView(teraStatus);
        page.addView(teraBox);

        // ---- WebDAV
        davBox = Ui.box(this);
        davUrl = Ui.field(this, davBox, "Folder URL (e.g. https://host/remote.php/dav/files/me/)", false);
        davUser = Ui.field(this, davBox, "Username", false);
        davPass = Ui.field(this, davBox, "Password", true);
        page.addView(davBox);

        status = Ui.note(this, "");
        page.addView(status);
        save = Ui.button(this, page, "Test and connect", v -> testAndSave());

        type.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                googleBox.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                megaBox.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
                teraBox.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
                davBox.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
                status.setText("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        type.setSelection(0);

        ScrollView sv = new ScrollView(this);
        sv.addView(page);
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(sv);
    }

    // ------------------------------------------------------------------ TeraBox: the real website in a web view

    private void startTeraBoxSignIn() {
        String site = teraSite.getText().toString().trim();
        if (site.isEmpty()) site = TeraBoxClient.DEFAULT_SITE;
        if (!site.contains("://")) site = "https://" + site;
        Intent i = new Intent(this, TeraBoxLoginActivity.class);
        i.putExtra(TeraBoxLoginActivity.EXTRA_SITE, site);
        startActivityForResult(i, REQUEST_TERABOX);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TERABOX && resultCode == RESULT_OK && data != null) {
            String cookie = data.getStringExtra(TeraBoxLoginActivity.EXTRA_COOKIE);
            if (cookie != null && !cookie.isEmpty()) {
                teraCookie = cookie;
                teraStatus.setText("Signed in to TeraBox. Now press \"Test and connect\".");
                status.setText("");
            }
        }
    }

    // ------------------------------------------------------------------ Google: system browser + loopback redirect

    private void startGoogleSignIn() {
        final String clientId = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_ID : googleId.getText().toString().trim();
        final String clientSecret = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_SECRET
                : googleSecret.getText().toString().trim();
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            status.setText("Enter the client ID and the client secret first.");
            return;
        }
        if (receiver != null) receiver.close();
        final LoopbackReceiver rx;
        try {
            rx = new LoopbackReceiver();
        } catch (IOException e) {
            status.setText("Could not start the sign-in: " + e.getMessage());
            return;
        }
        receiver = rx;
        final String verifier = GoogleOAuth.newVerifier();
        final String state = GoogleOAuth.newState();
        final String redirect = rx.redirectUri();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(GoogleOAuth.authUrl(clientId, redirect, state, GoogleOAuth.challenge(verifier)))));
        } catch (RuntimeException e) {
            rx.close();
            status.setText("Could not open the browser: " + e.getMessage());
            return;
        }
        status.setText("Finish signing in in your browser, then come back to Cloudy...");
        googleSignIn.setEnabled(false);
        new Thread(() -> {
            String failure = null, token = null, mail = "";
            try {
                String code = rx.awaitCode(state, 5 * 60 * 1000L);
                GoogleOAuth.Tokens t = GoogleOAuth.exchange(Clients.TRANSPORT, GoogleOAuth.TOKEN_URL, clientId,
                        clientSecret, code, verifier, redirect);
                token = t.refreshToken;
                try {
                    mail = new GoogleDriveClient(Clients.TRANSPORT, clientId, clientSecret, token).signedInEmail();
                } catch (Exception ignored) {
                    // the sign-in worked; the e-mail is only for display
                }
            } catch (Exception e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                rx.close();
            }
            final String fFailure = failure, fToken = token, fMail = mail;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                googleSignIn.setEnabled(true);
                if (fFailure != null) {
                    status.setText(fFailure);
                    return;
                }
                googleToken = fToken;
                googleEmail = fMail;
                googleStatus.setText("Signed in" + (fMail.isEmpty() ? "." : " as " + fMail));
                status.setText("Signed in. Now press \"Test and connect\".");
            });
        }).start();
    }

    // ------------------------------------------------------------------ test and save

    private Account readForm() {
        Account a = new Account();
        int t = type.getSelectedItemPosition();
        if (t == 0) {
            a.type = Account.Type.GOOGLE_DRIVE;
            a.username = googleEmail;
            a.secret = googleToken;
            a.clientId = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_ID : googleId.getText().toString().trim();
            a.clientSecret = builtInGoogle ? BuildConfig.GOOGLE_CLIENT_SECRET : googleSecret.getText().toString().trim();
        } else if (t == 1) {
            a.type = Account.Type.MEGA;
            a.username = megaEmail.getText().toString().trim();
            a.secret = megaPassword.getText().toString();
            a.clientId = megaKey.getText().toString().trim();
        } else if (t == 2) {
            a.type = Account.Type.TERABOX;
            String site = teraSite.getText().toString().trim();
            if (!site.isEmpty() && !site.contains("://")) site = "https://" + site;
            a.url = site.isEmpty() ? TeraBoxClient.DEFAULT_SITE : site;
            a.secret = teraCookie;
        } else {
            a.type = Account.Type.WEBDAV;
            a.url = davUrl.getText().toString().trim();
            a.username = davUser.getText().toString().trim();
            a.secret = davPass.getText().toString();
        }
        a.name = name.getText().toString().trim();
        if (a.name.isEmpty()) a.name = defaultName(a);
        return a;
    }

    private static String defaultName(Account a) {
        switch (a.type) {
            case GOOGLE_DRIVE: return a.username.isEmpty() ? "Google Drive" : "Google Drive (" + a.username + ")";
            case MEGA: return "MEGA (" + a.username + ")";
            case TERABOX: return "TeraBox";
            default: return "WebDAV";
        }
    }

    private void testAndSave() {
        final Account a = readForm();
        if (a.type == Account.Type.GOOGLE_DRIVE && a.secret.isEmpty()) {
            status.setText("Sign in with Google first.");
            return;
        }
        if (a.type == Account.Type.TERABOX && a.secret.isEmpty()) {
            status.setText("Sign in on the TeraBox website first.");
            return;
        }
        try {
            CloudClients.create(a, Clients.TRANSPORT);          // validates the settings
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
            return;
        }
        final String code = megaCode == null ? "" : megaCode.getText().toString().trim();
        status.setText("Testing the connection...");
        save.setEnabled(false);
        new Thread(() -> {
            String failure = null;
            try {
                if (a.type == Account.Type.MEGA) {
                    // Sign in for real (this is where a two-factor code is used) and keep only the session.
                    a.clientSecret = MegaClient.signIn(Clients.TRANSPORT, a.username, a.secret, code, a.clientId);
                }
                CloudClients.create(a, Clients.TRANSPORT).list("/");
            } catch (Exception e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String fFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                save.setEnabled(true);
                if (fFailure != null) {
                    status.setText(fFailure);
                    return;
                }
                a.id = AccountStore.newId();
                try {
                    AccountStore.upsert(this, a);
                } catch (RuntimeException e) {
                    status.setText("Could not save: " + e.getMessage());
                    return;
                }
                Clients.reset();
                setResult(RESULT_OK);
                finish();
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) receiver.close();
        super.onDestroy();
    }
}
