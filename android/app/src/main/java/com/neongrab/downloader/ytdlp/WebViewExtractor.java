package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebViewExtractor {

    public interface Callback {
        void onSuccess(String streamUrl, String title);
        void onError(String error);
    }

    private WebViewExtractor() {}

    public static void extract(Context context, String targetUrl, Callback callback) {
        // Must run on main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WebView webView = new WebView(context);
                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                
                // Use a modern desktop UA to avoid mobile site limitations or bot blocks
                settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");

                AtomicBoolean finished = new AtomicBoolean(false);
                final String[] pageTitle = {"Video"};
                final String[] foundUrl = {null};

                Runnable cleanup = () -> {
                    try {
                        webView.stopLoading();
                        webView.loadUrl("about:blank");
                        webView.destroy();
                    } catch (Exception ignored) {}
                };

                // Timeout after 20 seconds
                Handler timeoutHandler = new Handler(Looper.getMainLooper());
                Runnable timeoutRunnable = () -> {
                    if (finished.compareAndSet(false, true)) {
                        cleanup.run();
                        callback.onError("Timeout waiting for video stream");
                    }
                };
                timeoutHandler.postDelayed(timeoutRunnable, 20000);

                webView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onReceivedTitle(WebView view, String title) {
                        super.onReceivedTitle(view, title);
                        if (title != null && !title.isEmpty() && !title.contains("Just a moment") && !title.toLowerCase().contains("cloudflare")) {
                            pageTitle[0] = title;
                        }
                    }
                });

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        
                        // Look for typical video stream formats
                        boolean isMedia = false;
                        if (url.contains(".m3u8")) {
                            isMedia = true;
                        } else if (url.contains(".mp4") && !url.contains("ad") && !url.contains("tracker")) {
                            isMedia = true;
                        }

                        if (isMedia) {
                            if (finished.compareAndSet(false, true)) {
                                timeoutHandler.removeCallbacks(timeoutRunnable);
                                foundUrl[0] = url;
                                
                                // Return to UI thread to clean up and callback
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    cleanup.run();
                                    callback.onSuccess(foundUrl[0], pageTitle[0]);
                                });
                            }
                        }
                        return super.shouldInterceptRequest(view, request);
                    }
                });

                webView.loadUrl(targetUrl);

            } catch (Exception e) {
                callback.onError("WebView initialization failed: " + e.getMessage());
            }
        });
    }
}
