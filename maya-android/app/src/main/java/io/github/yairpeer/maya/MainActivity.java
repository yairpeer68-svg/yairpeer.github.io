package io.github.yairpeer.maya;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * מאיה — עטיפת אנדרואיד לטאבלט שליד מיטת המטופל.
 *
 * שני דברים שהעטיפה מוסיפה על הדפדפן:
 *  1. זיהוי דיבור מקורי — ל-WebView של אנדרואיד אין Web Speech API, ולכן
 *     האפליקציה חושפת גשר בשם MayaNative שמפעיל את SpeechRecognizer של המערכת
 *     ומחזיר את הטקסט לדף. בלי זה מאיה הייתה נשארת עם הקלדה בלבד.
 *  2. מסך שנשאר דלוק — הטאבלט ליד המיטה צריך להיות זמין בלחיצה אחת.
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://yairpeer68-svg.github.io/yairpeer.github.io/maya/";
    private static final int REQ_MIC = 101;
    private static final String HOST = "yairpeer68-svg.github.io";

    private WebView webView;
    private SpeechRecognizer recognizer;
    private boolean listening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // הטאבלט ליד המיטה לא צריך להיכבות באמצע שיחה
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#eaf2f5"));
        setContentView(webView);
        getWindow().setStatusBarColor(Color.parseColor("#0d9488"));
        getWindow().setNavigationBarColor(Color.parseColor("#eaf2f5"));

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // מאיה מדברת מיד כשיש תשובה, בלי שהמטופל יצטרך ללחוץ שוב על "נגן"
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new SpeechBridge(), "MayaNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri url = request.getUrl();
                String host = url.getHost();
                if (host != null && !host.contains(HOST)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // מאשר למיקרופון של הדף לעבוד (רלוונטי ל-WebView חדשים שכן תומכים)
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        // נטען מהאתר החי, כך שכל עדכון באתר מגיע אוטומטית גם לאפליקציה
        webView.loadUrl(APP_URL);
    }

    /* ---------- הגשר בין הדף לזיהוי הדיבור של אנדרואיד ---------- */
    private class SpeechBridge {

        @JavascriptInterface
        public boolean available() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void start() {
            runOnUiThread(MainActivity.this::startRecognition);
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                if (recognizer != null && listening) recognizer.stopListening();
            });
        }
    }

    private void startRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            toJs("error", "not-allowed");
            return;
        }
        if (listening) return;

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { listening = true; toJs("start", ""); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { listening = false; }

                @Override
                public void onError(int error) {
                    listening = false;
                    String code;
                    switch (error) {
                        case SpeechRecognizer.ERROR_NO_MATCH:
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: code = "no-speech"; break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "not-allowed"; break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: code = "network"; break;
                        default: code = "aborted";
                    }
                    toJs("error", code);
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    toJs("final", firstOf(results));
                }

                @Override
                public void onPartialResults(Bundle partial) {
                    String t = firstOf(partial);
                    if (!t.isEmpty()) toJs("partial", t);
                }

                @Override public void onEvent(int eventType, Bundle params) { }
            });
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // מטופל חולה מדבר לאט — לא לחתוך אותו אחרי חצי מילה
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L);

        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            toJs("error", "aborted");
        }
    }

    private String firstOf(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty() || list.get(0) == null) ? "" : list.get(0);
    }

    private void toJs(String kind, String text) {
        final String js = "window.__mayaSpeech && window.__mayaSpeech("
                + JSONObject.quote(kind) + "," + JSONObject.quote(text == null ? "" : text) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code == REQ_MIC) {
            boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
            toJs("permission", ok ? "granted" : "denied");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // מאיה לא מדברת לחדר ריק ולא מקשיבה כשאף אחד לא ביקש
        if (recognizer != null && listening) {
            recognizer.cancel();
            listening = false;
        }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
