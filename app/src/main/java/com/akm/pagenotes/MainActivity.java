package com.akm.pagenotes;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String REDIRECT = "https://www.facebook.com/connect/login_success.html";

    private static final int REQ_PICK = 1001;
    private static final int MAX_SIDE = 1080;

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
                boolean multi = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                try {
                    startActivityForResult(buildPicker(multi, true), REQ_PICK);
                } catch (Exception e1) {
                    try {
                        startActivityForResult(buildPicker(multi, false), REQ_PICK);
                    } catch (Exception e2) {
                        filePathCallback = null;
                        callback.onReceiveValue(null);
                        return false;
                    }
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

    /** গ্যালারি পিকার: Android 13+ এ ফটো পিকার, নইলে সাধারণ ফাইল পিকার */
    private Intent buildPicker(boolean multi, boolean modern) {
        Intent i;
        if (modern && Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if (multi) {
                i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
            }
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            if (multi) {
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
        }
        return i;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) {
            return;
        }
        final ValueCallback<Uri[]> cb = filePathCallback;
        filePathCallback = null;
        if (cb == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<Uri>();
        if (resultCode == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int k = 0; k < cd.getItemCount() && uris.size() < 10; k++) {
                    Uri u = cd.getItemAt(k).getUri();
                    if (u != null) {
                        uris.add(u);
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            cb.onReceiveValue(null);
            return;
        }
        // বড় / HEIC / ঘোরানো ছবি এখানেই (নেটিভ) ছোট JPEG করে ওয়েবে দেওয়া হয়
        new Thread(new Runnable() {
            @Override
            public void run() {
                File[] old = getCacheDir().listFiles();
                if (old != null) {
                    for (File f : old) {
                        if (f.getName().startsWith("pick_")) {
                            f.delete();
                        }
                    }
                }
                final List<Uri> out = new ArrayList<Uri>();
                for (int k = 0; k < uris.size(); k++) {
                    try {
                        File f = prepare(uris.get(k), k);
                        if (f != null) {
                            out.add(Uri.fromFile(f));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (out.isEmpty()) {
                            cb.onReceiveValue(null);
                            web.evaluateJavascript("toast('ছবি লোড করা যায়নি, অন্য ছবি বেছে দেখুন')", null);
                        } else {
                            cb.onReceiveValue(out.toArray(new Uri[0]));
                        }
                    }
                });
            }
        }).start();
    }

    private File prepare(Uri uri, int idx) throws Exception {
        ContentResolver cr = getContentResolver();
        Bitmap bmp = null;
        boolean applyExif = true;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) {
                in.close();
            }
        }
        if (o.outWidth > 0 && o.outHeight > 0) {
            int sample = 1;
            int m = Math.max(o.outWidth, o.outHeight);
            while (m / (sample * 2) >= MAX_SIDE) {
                sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            in = cr.openInputStream(uri);
            try {
                bmp = BitmapFactory.decodeStream(in, null, d);
            } finally {
                if (in != null) {
                    in.close();
                }
            }
        }
        if (bmp == null && Build.VERSION.SDK_INT >= 28) {
            // HEIC / AVIF ইত্যাদি — ImageDecoder নিজেই ঘুরিয়ে দেয়
            bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri),
                    new ImageDecoder.OnHeaderDecodedListener() {
                        @Override
                        public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                                    ImageDecoder.Source source) {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            int w = info.getSize().getWidth();
                            int h = info.getSize().getHeight();
                            float sc = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
                            decoder.setTargetSize(Math.max(1, Math.round(w * sc)),
                                    Math.max(1, Math.round(h * sc)));
                        }
                    });
            applyExif = false;
        }
        if (bmp == null) {
            return null;
        }

        Matrix mx = new Matrix();
        if (applyExif && Build.VERSION.SDK_INT >= 24) {
            try {
                InputStream ei = cr.openInputStream(uri);
                try {
                    int ori = new ExifInterface(ei).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    switch (ori) {
                        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                            mx.postScale(-1, 1);
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_180:
                            mx.postRotate(180);
                            break;
                        case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                            mx.postScale(1, -1);
                            break;
                        case ExifInterface.ORIENTATION_TRANSPOSE:
                            mx.postRotate(90);
                            mx.postScale(-1, 1);
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_90:
                            mx.postRotate(90);
                            break;
                        case ExifInterface.ORIENTATION_TRANSVERSE:
                            mx.postRotate(270);
                            mx.postScale(-1, 1);
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_270:
                            mx.postRotate(270);
                            break;
                        default:
                            break;
                    }
                } finally {
                    if (ei != null) {
                        ei.close();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        float s = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (s < 1f) {
            mx.postScale(s, s);
        }
        if (!mx.isIdentity()) {
            Bitmap t = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true);
            if (t != bmp) {
                bmp.recycle();
            }
            bmp = t;
        }
        if (bmp.hasAlpha()) {
            // PNG-র স্বচ্ছ অংশ JPEG-এ কালো হয়ে যায়, তাই সাদা ব্যাকগ্রাউন্ড
            Bitmap w = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(w);
            c.drawColor(Color.WHITE);
            c.drawBitmap(bmp, 0, 0, null);
            bmp.recycle();
            bmp = w;
        }
        File f = new File(getCacheDir(), "pick_" + System.currentTimeMillis() + "_" + idx + ".jpg");
        FileOutputStream fo = new FileOutputStream(f);
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fo);
        } finally {
            fo.close();
        }
        bmp.recycle();
        return f;
    }
}
