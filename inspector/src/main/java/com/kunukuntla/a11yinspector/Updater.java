package com.kunukuntla.a11yinspector;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/**
 * ⬆ Updates from inside the app: every build of this app's line (its branch) is also published
 * under one fixed address - gowtham-&lt;branch&gt; - with its build number in version.txt. The app
 * reads that number; when it is newer than its own, it downloads that APK and hands it to Android's
 * installer (Android asks you to confirm; the first time also to allow installs from this app).
 */
final class Updater {

    private static final String BASE = "https://github.com/KunukuntlaGowtham/dummy/releases/download/gowtham-";
    private static final String ACTION = "com.kunukuntla.a11yinspector.UPDATE_INSTALLED";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {
    }

    static int current() {
        return BuildConfig.VERSION_CODE;
    }

    static boolean enabled() {
        return !BuildConfig.UPDATE_CHANNEL.isEmpty();
    }

    /** The newest build's number on this app's line (on the main thread), or -1 when it can't be read. */
    static void latest(Consumer<Integer> then) {
        if (!enabled()) {
            then.accept(-1);
            return;
        }
        new Thread(() -> {
            int n = -1;
            try {
                HttpURLConnection c = open(BASE + BuildConfig.UPDATE_CHANNEL + "/version.txt");
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        byte[] b = new byte[64];
                        int len = in.read(b);
                        if (len > 0) n = Integer.parseInt(new String(b, 0, len).trim());
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            int result = n;
            MAIN.post(() -> then.accept(result));
        }, "update-check").start();
    }

    /**
     * Downloads the newest APK and starts installing it. The first time Android asks you to allow
     * installs from this app (Settings opens; come back and tap update again).
     */
    static void install(Activity a, Consumer<String> say) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            say.accept("Allow installs from Gowtham, then come back and tap Update again");
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Context app = a.getApplicationContext();
        listen(app);
        say.accept("Downloading the update…");
        new Thread(() -> {
            String error = null;
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection c = open(BASE + BuildConfig.UPDATE_CHANNEL + "/Gowtham.apk");
                    if (c.getResponseCode() != 200) throw new java.io.IOException("download: HTTP " + c.getResponseCode());
                    long size = c.getContentLengthLong();
                    try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("Gowtham.apk", 0, size)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        s.fsync(out);
                    }
                    c.disconnect();
                    Intent done = new Intent(ACTION).setPackage(app.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(app, id, done, flags).getIntentSender());
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String err = error;
            MAIN.post(() -> say.accept(err == null ? "Installing - confirm on the screen Android shows" : "Update failed: " + err));
        }, "update-install").start();
    }

    private static boolean listening;

    /** Android's answer: asks you to confirm (shown), installed, or failed (said). */
    private static void listen(Context app) {
        if (listening) return;
        listening = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(confirm);
                    }
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Toast.makeText(c, "Update not installed" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        // GitHub sends the file from another address (a redirect): followed by hand, a few times.
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) break;
                url = next;
                continue;
            }
            return c;
        }
        throw new java.io.IOException("too many redirects");
    }
}
