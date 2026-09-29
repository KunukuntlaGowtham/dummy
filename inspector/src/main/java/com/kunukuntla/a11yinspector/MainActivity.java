package com.kunukuntla.a11yinspector;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

/**
 * The inspector's own screen. It explains the tool, lets you turn the
 * accessibility service on, and shows the last read-only report captured from
 * another app. Nothing here (or anywhere in this app) taps or changes another
 * app - it only reads what apps expose to the accessibility framework.
 */
public class MainActivity extends Activity {

    private static final int GREEN = 0xFF2E7D32;
    private TextView status;
    private TextView report;
    private Button budget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(GREEN);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView blurb = new TextView(this);
        blurb.setText(R.string.a11y_description);
        blurb.setTextColor(Color.DKGRAY);
        blurb.setTextSize(13);
        blurb.setPadding(0, dp(8), 0, dp(12));
        root.addView(blurb);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        root.addView(button(getString(R.string.btn_settings), v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(button(getString(R.string.btn_refresh), v -> showReport()));

        root.addView(button(getString(R.string.btn_copy), v -> copyReport()));

        budget = new Button(this);
        budget.setAllCaps(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(6);
        budget.setLayoutParams(blp);
        budget.setOnClickListener(v -> {
            Limits.cycle(this);
            updateBudgetLabel();
            toast(getString(R.string.budget_changed));
        });
        root.addView(budget);

        // The report can be wide and tall, so scroll both ways in a monospace view.
        report = new TextView(this);
        report.setTextSize(11);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextColor(Color.BLACK);
        report.setTextIsSelectable(true);
        report.setPadding(dp(8), dp(8), dp(8), dp(8));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(report);
        ScrollView vs = new ScrollView(this);
        vs.addView(hs);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(vs, lp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        updateBudgetLabel();
        showReport();
    }

    private void updateBudgetLabel() {
        budget.setText(getString(R.string.btn_budget, Limits.maxNodes(this)));
    }

    private void refreshStatus() {
        if (isServiceEnabled()) {
            status.setText(R.string.status_on);
            status.setTextColor(GREEN);
        } else {
            status.setText(R.string.status_off);
            status.setTextColor(Color.RED);
        }
    }

    private void showReport() {
        String r = Latest.report();
        if (TextUtils.isEmpty(r)) {
            report.setText(R.string.empty_report);
            return;
        }
        String when = DateFormat.getTimeInstance().format(new Date(Latest.time()));
        report.setText("Captured " + when + "\n\n" + r);
    }

    private void copyReport() {
        String r = Latest.report();
        if (TextUtils.isEmpty(r)) {
            toast(getString(R.string.nothing_to_copy));
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("a11y-report", r));
        toast(getString(R.string.copied));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName me = new ComponentName(this, InspectorService.class);
        String flat = me.flattenToString();
        String flatShort = me.flattenToShortString();
        for (String part : enabled.split(":")) {
            if (part.equalsIgnoreCase(flat) || part.equalsIgnoreCase(flatShort)) {
                return true;
            }
        }
        return false;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
