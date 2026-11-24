package com.kunukuntla.binpress;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Bin Press: type the numbers, turn the service on, then press 🗑 Go on the sevaks list. */
public class MainActivity extends Activity {

    private EditText numbers, backs;
    private TextView status, log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Bin Press");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(title);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        page.addView(status);

        Button open = new Button(this);
        open.setText("Open Accessibility settings");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(open);

        page.addView(label("Numbers to delete (as numbered on the sevaks list)"));
        numbers = new EditText(this);
        numbers.setHint("e.g. 3, 7, 11");
        numbers.setInputType(InputType.TYPE_CLASS_TEXT);
        page.addView(numbers, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(label("Back presses when a pop-up comes (1 - 3)"));
        backs = new EditText(this);
        backs.setInputType(InputType.TYPE_CLASS_NUMBER);
        page.addView(backs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(v -> save());
        page.addView(save);

        TextView how = new TextView(this);
        how.setText("How: turn Bin Press on in Accessibility, open the sevaks list in the booking app, then tap the red 🗑 Go "
                + "button. It deletes from the highest number down (so your numbers stay right): taps the bin, presses Back when "
                + "a pop-up comes, checks the profile is gone, and goes on. Tap the button again to stop.");
        how.setPadding(0, pad, 0, pad / 2);
        page.addView(how);

        page.addView(label("Last run"));
        log = new TextView(this);
        log.setTextIsSelectable(true);
        page.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, 24, 0, 4);
        return t;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(BinService.PREFS, Context.MODE_PRIVATE);
    }

    private void save() {
        List<Integer> list = Numbers.parse(numbers.getText().toString());
        int b = 1;
        try {
            b = Integer.parseInt(backs.getText().toString().trim());
        } catch (NumberFormatException ignored) {
        }
        b = Math.max(1, Math.min(3, b));
        prefs().edit().putString(BinService.KEY_NUMBERS, numbers.getText().toString().trim()).putInt(BinService.KEY_BACKS, b).apply();
        backs.setText(String.valueOf(b));
        Toast.makeText(this, list.isEmpty() ? "Saved - but no numbers found" : "Saved: " + list + " (highest first)",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        numbers.setText(prefs().getString(BinService.KEY_NUMBERS, ""));
        backs.setText(String.valueOf(prefs().getInt(BinService.KEY_BACKS, 1)));
        boolean on = BinService.connected;
        status.setText(on ? "● Service is on - open the sevaks list and tap 🗑 Go"
                : "○ Service is off - turn Bin Press on in Accessibility settings");
        String last = prefs().getString(BinService.KEY_LOG, "");
        log.setText(last.isEmpty() ? "Nothing yet." : last);
    }
}
