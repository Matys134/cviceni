package cz.matys.rovneasilne;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Shell around the web app in assets (the Rovne-a-silne folder). Adds what a browser tab can't do well:
 * keeping the screen on during timers, the share sheet, and updating itself from GitHub Releases.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/Rovne-a-silne.html";
    private static final int REQ_FILE = 1;
    private static final int REQ_INSTALL_PERMISSION = 2;
    /** Automatic checks run at most this often; the button in the app checks right away. */
    private static final long AUTO_CHECK_MS = 6L * 60 * 60 * 1000;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private long downloadId = -1;
    private Uri pendingInstall;
    private String pendingApkUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);

        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadDone, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadDone, f);

        checkForUpdate(false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(downloadDone);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        } else if (requestCode == REQ_INSTALL_PERMISSION && pendingInstall != null) {
            if (getPackageManager().canRequestPackageInstalls()) install(pendingInstall);
            else toast("Bez povolení se aktualizace nenainstaluje. Zkus to znovu přes Zkontrolovat aktualizace.");
        }
    }

    /** Called from the page as window.Android.*. */
    private class Bridge {
        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Rovně a silně – záloha")
                        .putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Uložit zálohu"));
            });
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> checkForUpdate(true));
        }
    }

    /* ---------------- Updates from GitHub Releases ---------------- */

    private void checkForUpdate(boolean manual) {
        SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("lastCheck", 0) < AUTO_CHECK_MS) return;
        prefs.edit().putLong("lastCheck", now).apply();
        if (manual) toast("Hledám novou verzi…");

        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest").openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                JSONObject release = new JSONObject(readAll(c.getInputStream()));
                String tag = release.getString("tag_name");
                String notes = release.optString("body", "").trim();
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url");
                }
                final String url = apkUrl;
                boolean newer = compareVersions(tag, BuildConfig.VERSION_NAME) > 0;
                runOnUiThread(() -> {
                    if (newer && url != null) showUpdateDialog(tag, notes, url);
                    else if (manual) toast("Máš nejnovější verzi (" + BuildConfig.VERSION_NAME + ").");
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> toast("Aktualizaci se nepodařilo zkontrolovat. Jsi připojený k internetu?"));
            }
        }).start();
    }

    private void showUpdateDialog(String tag, String notes, String apkUrl) {
        if (isFinishing()) return;
        String msg = (notes.isEmpty() ? "" : (notes.length() > 900 ? notes.substring(0, 900) + "…" : notes) + "\n\n")
                + "Tvoje záznamy zůstanou zachované.";
        new AlertDialog.Builder(this)
                .setTitle("Nová verze " + tag.replaceFirst("^v", ""))
                .setMessage(msg)
                .setPositiveButton("Stáhnout a nainstalovat", (d, w) -> download(tag, apkUrl))
                .setNegativeButton("Později", null)
                .show();
    }

    private void download(String tag, String apkUrl) {
        pendingApkUrl = apkUrl;
        try {
            DownloadManager dm = getSystemService(DownloadManager.class);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("Rovně a silně " + tag)
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "rovne-a-silne-" + tag + ".apk");
            downloadId = dm.enqueue(r);
            toast("Stahuji novou verzi…");
        } catch (Exception e) {
            openExternal(Uri.parse(apkUrl));
        }
    }

    private final BroadcastReceiver downloadDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != downloadId) return;
            Uri uri = getSystemService(DownloadManager.class).getUriForDownloadedFile(id);
            if (uri == null) {
                toast("Stažení se nepovedlo, otevírám odkaz v prohlížeči.");
                if (pendingApkUrl != null) openExternal(Uri.parse(pendingApkUrl));
                return;
            }
            install(uri);
        }
    };

    private void install(Uri apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = apk;
            new AlertDialog.Builder(this)
                    .setTitle("Povol instalaci aktualizací")
                    .setMessage("Android potřebuje jednou povolit, aby tahle aplikace mohla instalovat své aktualizace. "
                            + "Na další obrazovce zapni „Povolit z tohoto zdroje“ a vrať se zpátky.")
                    .setPositiveButton("Otevřít nastavení", (d, w) -> startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())),
                            REQ_INSTALL_PERMISSION))
                    .setNegativeButton("Zrušit", null)
                    .show();
            return;
        }
        pendingInstall = null;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            if (pendingApkUrl != null) openExternal(Uri.parse(pendingApkUrl));
        }
    }

    /** Compares "v1.2.3"-style versions numerically; missing parts count as 0. */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("[.-]");
        String[] y = b.replaceFirst("^v", "").split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parseIntSafe(x[i]) : 0;
            int q = i < y.length ? parseIntSafe(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
