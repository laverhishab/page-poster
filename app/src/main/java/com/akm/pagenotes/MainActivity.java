package com.akm.pagenotes;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final String REDIRECT = "https://www.facebook.com/connect/login_success.html";

    private ValueCallback<Uri[]> filePathCallback;
    private WebView web;
    private Dialog fbDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(pick, "ছবি বাছুন"), 1001);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/akm_notes.html");
        setContentView(web);
    }

    /** JS থেকে ডাকা হয়: Android.fbLogin(appId), Android.openFb() */
    private class Bridge {
        @JavascriptInterface
        public void fbLogin(final String appId) {
            final String url = "https://www.facebook.com/v21.0/dialog/oauth?client_id=" + Uri.encode(appId)
                    + "&redirect_uri=" + Uri.encode(REDIRECT)
                    + "&response_type=token&scope=" + Uri.encode("pages_show_list,pages_manage_posts,pages_read_engagement");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showFb(url, true);
                }
            });
        }

        @JavascriptInterface
        public void setAuto(String json) {
            getSharedPreferences(AutoPost.PREF, 0).edit().putString("cfg", json).putBoolean("on", true)
                    .putString("done", "[]").apply();
            AutoJobService.start(getApplicationContext());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    AutoPost.run(getApplicationContext());
                }
            }).start();
        }

        @JavascriptInterface
        public void stopAuto() {
            getSharedPreferences(AutoPost.PREF, 0).edit().putBoolean("on", false).apply();
            AutoJobService.stop(getApplicationContext());
        }

        @JavascriptInterface
        public String autoStatus() {
            android.content.SharedPreferences sp = getSharedPreferences(AutoPost.PREF, 0);
            return (sp.getBoolean("on", false) ? "চালু ✅" : "বন্ধ") + "\n" + sp.getString("status", "এখনো চলেনি");
        }

        @JavascriptInterface
        public void openFb() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showFb("https://m.facebook.com", false);
                }
            });
        }
    }

    private void showFb(String url, final boolean isLogin) {
        if (fbDialog != null) {
            fbDialog.dismiss();
        }
        final WebView w = new WebView(this);
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // Facebook লগইন যেন WebView চিনে ব্লক না করে
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            private boolean handle(String u) {
                if (isLogin && u != null && u.startsWith(REDIRECT)) {
                    String token = null;
                    try {
                        token = Uri.parse(u.replace('#', '?')).getQueryParameter("access_token");
                    } catch (Exception ignored) {
                    }
                    if (fbDialog != null) {
                        fbDialog.dismiss();
                    }
                    if (token != null) {
                        web.evaluateJavascript("window.onFbToken&&onFbToken('" + token.replace("'", "") + "')", null);
                    } else {
                        web.evaluateJavascript("toast('লগইন হয়নি, আবার চেষ্টা করুন')", null);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return handle(u);
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                handle(u);
            }
        });

        fbDialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                if (w.canGoBack()) {
                    w.goBack();
                } else {
                    dismiss();
                }
            }
        };
        fbDialog.setContentView(w, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fbDialog.show();
        w.loadUrl(url);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && filePathCallback != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    res = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        res[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    res = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(res);
            filePathCallback = null;
        }
    }
}
