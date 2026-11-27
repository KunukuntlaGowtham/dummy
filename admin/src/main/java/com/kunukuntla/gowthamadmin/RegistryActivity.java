package com.kunukuntla.gowthamadmin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🧾 Sevak Registry (the formfiller site) inside the admin app. It loads the LIVE site, so every
 * deploy reaches the phone with no new APK. What a plain WebView can't do is handled here:
 * downloads (Excel, contacts .vcf / .csv, settings .json) saved to Downloads with Open / Share;
 * photo and file pickers with the camera; print / PDF pop-ups; WhatsApp and other outside links;
 * a "no internet - retry" screen; sign-in kept (localStorage); Back asks before leaving; a
 * keep-awake switch for the CONTROL tab.
 */
public class RegistryActivity extends Activity {

    static final String SITE = "https://kunukuntlagowtham.github.io/formfiller/";
    private static final String HOST = "kunukuntlagowtham.github.io";
    private static final int REQ_FILE = 41, REQ_STORAGE = 42;

    private WebView web;
    private ProgressBar bar;
    private View errorView;
    private TextView awakeButton, phoneButton;
    /** The phone layout laid over the site (assets/registry-mobile.css). */
    private String mobileCss = "";
    private Dialog popup;
    private boolean mainError;

    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    /** A download waiting for the storage permission (Android 9 and older only). */
    private byte[] pendingBytes;
    private String pendingMime, pendingName;

    // ---- the screen ----

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setBackgroundColor(0xFF0F0F0F);
        tools.setPadding(dp(12), dp(6), dp(6), dp(6));
        TextView title = new TextView(this);
        title.setText("🧾 Registry");
        title.setTextColor(0xFFFFE500);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        tools.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        phoneButton = tool("");
        phoneButton.setOnClickListener(v -> {
            prefs().edit().putBoolean("phone", !phoneLayout()).apply();
            showPhoneButton();
            applyLayout(web);
        });
        tools.addView(phoneButton);
        awakeButton = tool("");
        awakeButton.setOnClickListener(v -> setAwake(!awake()));
        tools.addView(awakeButton);
        TextView reload = tool("↻");
        reload.setOnClickListener(v -> {
            hideError();
            web.reload();
        });
        tools.addView(reload);
        TextView close = tool("✕");
        close.setOnClickListener(v -> askLeave());
        tools.addView(close);
        root.addView(tools);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this);
        setUp(web, true);
        content.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        errorView = errorScreen();
        errorView.setVisibility(View.GONE);
        content.addView(errorView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        mobileCss = readAsset("registry-mobile.css");
        showPhoneButton();
        setAwake(awake());
        if (saved == null || web.restoreState(saved) == null) web.loadUrl(SITE);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        // The page's JavaScript keeps going (CONTROL polls); only animations etc. pause.
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (popup != null) popup.dismiss();
        web.destroy();
        super.onDestroy();
    }

    /** Back: closes a pop-up, then goes back in the site, then asks before leaving (never mid-form). */
    @Override
    public void onBackPressed() {
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            askLeave();
        }
    }

    private void askLeave() {
        new AlertDialog.Builder(this).setTitle("Leave Sevak Registry?")
                .setMessage("Anything not saved on the form will be lost.")
                .setPositiveButton("Leave", (d, w) -> finish())
                .setNegativeButton("Stay", null).show();
    }

    // ---- keep awake (for the CONTROL tab and timed TTD scripts) ----

    private SharedPreferences prefs() {
        return getSharedPreferences("registry", MODE_PRIVATE);
    }

    private boolean awake() {
        return prefs().getBoolean("awake", false);
    }

    private void setAwake(boolean on) {
        prefs().edit().putBoolean("awake", on).apply();
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        awakeButton.setText(on ? "☀ Awake" : "☾ Sleep ok");
        awakeButton.setTextColor(on ? 0xFFFFE500 : 0xFFA5A5A5);
    }

    // ---- the phone layout ----

    private boolean phoneLayout() {
        return prefs().getBoolean("phone", true);
    }

    private void showPhoneButton() {
        phoneButton.setText(phoneLayout() ? "📱 Phone" : "🖥 Desktop");
    }

    private String readAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The site laid out for the phone (or back to the laptop layout): a style sheet put into the
     * page - the site itself is unchanged - and the chosen tab kept in view in the swipeable tab strip.
     */
    private void applyLayout(WebView view) {
        String js;
        if (!phoneLayout() || mobileCss.isEmpty()) {
            js = "(function(){var s=document.getElementById('gowtham-mobile');if(s)s.remove();})();";
        } else {
            js = "(function(){var s=document.getElementById('gowtham-mobile');"
                    + "if(!s){s=document.createElement('style');s.id='gowtham-mobile';(document.head||document.documentElement).appendChild(s);}"
                    + "s.textContent=" + org.json.JSONObject.quote(mobileCss) + ";"
                    + "if(window.__gowthamTabs)return;window.__gowthamTabs=1;"
                    + "document.addEventListener('click',function(){setTimeout(function(){"
                    + "if(!document.getElementById('gowtham-mobile'))return;"
                    + "var n=document.querySelector('.nav-tabs'),a=n&&n.querySelector('.tab-btn.active');if(!a)return;"
                    + "var ar=a.getBoundingClientRect(),nr=n.getBoundingClientRect();"
                    + "n.scrollBy({left:(ar.left+ar.width/2)-(nr.left+nr.width/2),behavior:'smooth'});},80);},true);"
                    + "})();";
        }
        view.evaluateJavascript(js, null);
    }

    // ---- the WebView, set up for the site ----

    private void setUp(WebView w, boolean main) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // sign-in, team, room, panels live in localStorage
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);      // print / PDF pop-ups, WhatsApp links
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100)); // the phone's font size
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(w), "GowthamApp");
        w.setWebViewClient(new Client(main));
        w.setWebChromeClient(new Chrome());
        // A plain http(s) file link (not made in the page): Android's downloader.
        w.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) return; // the page hook handles these
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, disposition, mime));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                toast("Downloading…");
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
            }
        });
    }

    /**
     * Put into each page: a download made in the page (a Blob or data link with "download") is
     * read and handed to the app to save; the page's quick "revoke" of the link is delayed so
     * the read can finish; window.print() goes to Android's printer (Save as PDF).
     */
    private static final String HOOK = "(function(){if(window.__gowthamHook)return;window.__gowthamHook=1;"
            + "var rev=URL.revokeObjectURL.bind(URL);URL.revokeObjectURL=function(u){setTimeout(function(){rev(u);},120000);};"
            + "function send(b,n){var r=new FileReader();r.onload=function(){var s=String(r.result);"
            + "GowthamApp.save(s.substring(s.indexOf(',')+1),b.type||'',n||'download');};r.onerror=function(){GowthamApp.fail('read');};r.readAsDataURL(b);}"
            + "function grab(a){var h=a.href||'';if(!a.hasAttribute('download')||(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0))return false;"
            + "var n=a.getAttribute('download')||'download';fetch(h).then(function(x){return x.blob();}).then(function(b){send(b,n);})"
            + ".catch(function(e){GowthamApp.fail(String(e));});return true;}"
            + "var click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(grab(this))return;return click.apply(this,arguments);};"
            + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;"
            + "if(a&&grab(a))e.preventDefault();},true);"
            + "window.print=function(){GowthamApp.print();};})();";

    private class Client extends WebViewClient {
        private final boolean main;

        Client(boolean main) {
            this.main = main;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return outside(view, req.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // A pop-up that is really a link out (WhatsApp, a group link): opened outside, pop-up closed.
            if (!main && url != null && outside(view, Uri.parse(url))) return;
            if (main) {
                mainError = false;
                bar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (main) applyLayout(view); // as soon as the page shows, not only once it has finished loading
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(HOOK, null);
            if (main) applyLayout(view);
            if (main) {
                bar.setVisibility(View.GONE);
                if (!mainError) hideError();
                CookieManager.getInstance().flush();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (main && req.isForMainFrame()) {
                mainError = true;
                showError();
            }
        }
    }

    /** True (and opened outside) for anything that isn't the site: WhatsApp, maps, phone, mail ... */
    private boolean outside(WebView view, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("javascript")) return false;
        boolean site = (scheme.equals("https") || scheme.equals("http")) && HOST.equalsIgnoreCase(uri.getHost());
        if (site) return false;
        try {
            Intent i = scheme.equals("intent") ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception e) {
            toast("No app can open this link");
        }
        if (view != web) web.post(() -> {
            if (popup != null) popup.dismiss(); // the empty pop-up the link came in
        });
        return true;
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            if (view == web) bar.setProgress(p);
        }

        /** window.open: a pop-up (the PDF roster, ID cards, print) shown full screen with Print / Close. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message result) {
            WebView child = new WebView(RegistryActivity.this);
            setUp(child, false);
            showPopup(child);
            WebView.WebViewTransport t = (WebView.WebViewTransport) result.obj;
            t.setWebView(child);
            result.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (popup != null) popup.dismiss();
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            return openPicker(params);
        }
    }

    // ---- pop-ups: print / save as PDF ----

    private void showPopup(WebView child) {
        if (popup != null) popup.dismiss();
        Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setBackgroundColor(0xFF0F0F0F);
        tools.setPadding(dp(12), dp(6), dp(6), dp(6));
        TextView t = new TextView(this);
        t.setText("Print / PDF");
        t.setTextColor(0xFFFFE500);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        tools.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView print = tool("🖨 Print / Save PDF");
        print.setOnClickListener(v -> print(child));
        tools.addView(print);
        TextView close = tool("✕");
        close.setOnClickListener(v -> d.dismiss());
        tools.addView(close);
        box.addView(tools);
        box.addView(child, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        d.setOnDismissListener(x -> {
            child.destroy();
            if (popup == d) popup = null;
        });
        popup = d;
        d.show();
    }

    private void print(WebView w) {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) {
            toast("Printing isn't available on this phone");
            return;
        }
        String name = "Sevak Registry";
        pm.print(name, w.createPrintDocumentAdapter(name), new PrintAttributes.Builder().build());
    }

    // ---- photo / file picker, with the camera ----

    private boolean openPicker(WebChromeClient.FileChooserParams params) {
        List<String> mimes = new ArrayList<>();
        boolean image = false;
        for (String a : params.getAcceptTypes()) {
            for (String one : a.split(",")) {
                String m = one.trim().toLowerCase(Locale.ROOT);
                if (m.isEmpty()) continue;
                if (m.startsWith("image")) image = true;
                if (m.contains("/")) mimes.add(m);
                else mimes.add("*/*"); // ".csv", ".json" ...: any file
            }
        }
        if (mimes.isEmpty()) {
            mimes.add("*/*");
            image = true;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        if (mimes.size() == 1 || mimes.contains("*/*")) {
            pick.setType(mimes.contains("*/*") ? "*/*" : mimes.get(0));
        } else {
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        }
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        Intent camera = image ? cameraIntent() : null;
        Intent start;
        if (camera != null && params.isCaptureEnabled()) {
            start = camera; // capture="environment": straight to the camera
        } else {
            start = Intent.createChooser(pick, "Photo or file");
            if (camera != null) start.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
        }
        try {
            startActivityForResult(start, REQ_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
            toast("No app to pick a file");
            return false;
        }
    }

    /** The camera, saving into Pictures/SevakRegistry (no camera permission needed). */
    private Intent cameraIntent() {
        cameraUri = null;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "sevak-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SevakRegistry");
            } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return null; // Android 9 and older without storage permission: files only
            }
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            cameraUri = null;
        }
        if (cameraUri == null) return null;
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i.resolveActivity(getPackageManager()) != null ? i : null;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_FILE || fileCallback == null) return;
        Uri[] picked = null;
        if (result == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                picked = new Uri[n];
                for (int i = 0; i < n; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) {
                picked = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                picked = new Uri[]{cameraUri}; // the photo just taken
            }
        }
        if ((picked == null || picked[0] != cameraUri) && cameraUri != null) {
            try {
                getContentResolver().delete(cameraUri, null, null); // the camera's empty slot
            } catch (Exception ignored) {
            }
        }
        cameraUri = null;
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    // ---- downloads made in the page: saved to Downloads, then Open / Share ----

    private class Bridge {
        private final WebView owner;

        Bridge(WebView owner) {
            this.owner = owner;
        }

        @JavascriptInterface
        public void save(String base64, String mime, String name) {
            byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                runOnUiThread(() -> toast("Download failed: bad data"));
                return;
            }
            runOnUiThread(() -> saveFile(bytes, mime, name));
        }

        @JavascriptInterface
        public void fail(String why) {
            runOnUiThread(() -> toast("Download failed: " + why));
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> RegistryActivity.this.print(owner));
        }
    }

    private void saveFile(byte[] bytes, String mime, String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (mime == null || mime.isEmpty()) mime = guessMime(safe);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("no space in Downloads");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("can't write");
                    out.write(bytes);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                saved(uri, mime, safe);
            } catch (Exception e) {
                toast("Couldn't save " + safe + ": " + e.getMessage());
            }
            return;
        }
        // Android 8 / 9: the public Downloads folder needs the storage permission.
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingBytes = bytes;
            pendingMime = mime;
            pendingName = safe;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("no Downloads folder");
            File f = new File(dir, safe);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
            String m = mime;
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{m},
                    (path, uri) -> runOnUiThread(() -> saved(uri, m, safe)));
        } catch (Exception e) {
            toast("Couldn't save " + safe + ": " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_STORAGE || pendingBytes == null) return;
        byte[] b = pendingBytes;
        pendingBytes = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            saveFile(b, pendingMime, pendingName);
        } else {
            toast("Without storage permission the file can't go to Downloads");
        }
    }

    private void saved(Uri uri, String mime, String name) {
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("Saved to Downloads").setMessage(name);
        if (uri != null) {
            d.setPositiveButton(name.toLowerCase(Locale.ROOT).endsWith(".vcf") ? "Add to Contacts" : "Open", (x, w) -> {
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(open);
                } catch (ActivityNotFoundException e) {
                    toast("No app opens this file - use Share");
                }
            });
            d.setNeutralButton("Share", (x, w) -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, "Share " + name));
            });
        }
        d.setNegativeButton("Done", null).show();
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".vcf")) return "text/x-vcard";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    // ---- no internet ----

    private View errorScreen() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFFF7F4EF);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        TextView t = new TextView(this);
        t.setText("📡 No internet\n\nSevak Registry needs the internet - it works on your live records.");
        t.setTextColor(0xFF0F0F0F);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        Button retry = new Button(this);
        retry.setText("Retry");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            hideError();
            web.loadUrl(SITE);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        box.addView(retry, lp);
        box.setClickable(true); // nothing behind it is tapped
        return box;
    }

    private void showError() {
        errorView.setVisibility(View.VISIBLE);
        bar.setVisibility(View.GONE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    // ---- small helpers ----

    private TextView tool(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(14);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
