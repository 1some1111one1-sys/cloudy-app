package com.cloudy.app;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny helpers so the screens can be built in code without layout files. */
final class Ui {
    private Ui() {
    }

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static LinearLayout box(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static TextView label(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(16);
        t.setPadding(0, dp(a, 8), 0, dp(a, 4));
        return t;
    }

    static TextView note(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(13);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(a, 6), 0, dp(a, 6));
        return t;
    }

    static EditText field(Activity a, LinearLayout parent, String hint, boolean secret) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    static Button button(Activity a, LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }
}
