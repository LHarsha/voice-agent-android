package com.voiceagent;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The assistant overlay: opens with a glowing orb, listens right away, answers out loud.
 * Tap the orb to talk again, tap the keyboard to type, tap outside to dismiss.
 */
public class AssistantActivity extends Activity implements Brain.Host {

    public static final String EXTRA_LISTEN = "listen";

    private static final int REQ_MIC = 1;
    private static final int REQ_COMMAND = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Brain brain;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String queuedSpeech;
    private boolean queuedListen;
    private SpeechRecognizer recognizer;

    private OrbView orb;
    private TextView status;
    private TextView live;
    private LinearLayout log;
    private ScrollView scroll;
    private LinearLayout inputRow;
    private EditText input;
    private TextView setupChip;

    private boolean listenAfterSpeech;
    private boolean closeAfterSpeech;
    private String retryAfterPermission;
    private int busyRetries;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(buildUi());
        brain = new Brain(this);

        tts = new TextToSpeech(this, statusCode -> {
            if (statusCode != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(Locale.getDefault());
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { ui.post(() -> orb.setState(OrbView.State.SPEAKING)); }
                @Override public void onDone(String id) { ui.post(AssistantActivity.this::afterSpeech); }
                @Override public void onError(String id) { ui.post(AssistantActivity.this::afterSpeech); }
                @Override public void onStop(String id, boolean interrupted) { /* we started listening/closed */ }
            });
            ttsReady = true;
            if (queuedSpeech != null) {
                String q = queuedSpeech;
                boolean l = queuedListen;
                queuedSpeech = null;
                speak(q, l);
            }
        });

        if (savedInstanceState == null) startListening();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        closeAfterSpeech = false;
        startListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetupChip();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (recognizer != null) recognizer.cancel();
        if (orb.getState() == OrbView.State.LISTENING) setIdle(null);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        brain.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#F21A1530"), Color.parseColor("#FA0B0B1A")});
        float r = dp(32);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        int pad = dp(20);
        panel.setPadding(pad, dp(14), pad, dp(12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, plp);

        // grab handle
        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(Color.parseColor("#55FFFFFF"));
        hb.setCornerRadius(dp(3));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(10);
        panel.addView(handle, hlp);

        setupChip = new TextView(this);
        setupChip.setText("✦  Make me your phone's assistant  ›");
        setupChip.setTextColor(Color.parseColor("#D9CCFF"));
        setupChip.setTextSize(13);
        setupChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Color.parseColor("#332A1F66"));
        cb.setStroke(dp(1), Color.parseColor("#667C5CFF"));
        cb.setCornerRadius(dp(18));
        setupChip.setBackground(cb);
        setupChip.setOnClickListener(v -> openAssistantSettings());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.bottomMargin = dp(8);
        panel.addView(setupChip, clp);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            int want = Math.min(log.getHeight(), maxH);
            if (lp.height != want) {
                lp.height = want;
                scroll.setLayoutParams(lp);
            }
        });

        live = new TextView(this);
        live.setTextColor(Color.parseColor("#B3FFFFFF"));
        live.setTextSize(20);
        live.setGravity(Gravity.CENTER_HORIZONTAL);
        live.setPadding(0, dp(6), 0, 0);
        panel.addView(live, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#80FFFFFF"));
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(4), 0, 0);
        panel.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Orb row with keyboard + close buttons
        FrameLayout orbRow = new FrameLayout(this);
        orb = new OrbView(this);
        orb.setContentDescription("Talk");
        orb.setOnClickListener(v -> {
            if (orb.getState() == OrbView.State.LISTENING && recognizer != null) recognizer.stopListening();
            else startListening();
        });
        orbRow.addView(orb, new FrameLayout.LayoutParams(dp(120), dp(120), Gravity.CENTER));
        TextView kb = roundButton("⌨");
        kb.setContentDescription("Type instead");
        kb.setOnClickListener(v -> toggleKeyboard());
        orbRow.addView(kb, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView close = roundButton("✕");
        close.setContentDescription("Close");
        close.setOnClickListener(v -> finish());
        orbRow.addView(close, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        panel.addView(orbRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)));

        // Type-instead row
        inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setVisibility(View.GONE);
        input = new EditText(this);
        input.setHint("Type a request…");
        input.setHintTextColor(Color.parseColor("#66FFFFFF"));
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(Color.parseColor("#22FFFFFF"));
        ib.setCornerRadius(dp(22));
        input.setBackground(ib);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitTyped();
                return true;
            }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = roundButton("➤");
        send.setOnClickListener(v -> submitTyped());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
        slp.leftMargin = dp(8);
        inputRow.addView(send, slp);
        panel.addView(inputRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Keep the panel above the navigation bar and keyboard (edge-to-edge on Android 15).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            panel.setPadding(pad, dp(14), pad, dp(12) + bottom);
            return insets;
        });
        return root;
    }

    private TextView roundButton(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.parseColor("#CCFFFFFF"));
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.parseColor("#22FFFFFF"));
        b.setBackground(d);
        return b;
    }

    private void addBubble(String text, boolean fromUser) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextIsSelectable(!fromUser);
        if (fromUser) {
            t.setTextColor(Color.parseColor("#99FFFFFF"));
            t.setTextSize(15);
            t.setGravity(Gravity.END);
            t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        } else {
            t.setTextColor(Color.WHITE);
            t.setTextSize(19);
            t.setLineSpacing(0, 1.15f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(fromUser ? 10 : 4);
        log.addView(t, lp);
        while (log.getChildCount() > 14) log.removeViewAt(0);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputRow.getVisibility() == View.VISIBLE) {
            inputRow.setVisibility(View.GONE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            return;
        }
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
        setIdle("Type your request");
        inputRow.setVisibility(View.VISIBLE);
        input.requestFocus();
        input.post(() -> imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT));
    }

    private void submitTyped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        addBubble(text, true);
        live.setText("");
        orb.setState(OrbView.State.THINKING);
        brain.handle(text);
    }

    private void setIdle(String message) {
        orb.setState(OrbView.State.IDLE);
        status.setText(message != null ? message : "Tap the orb to talk");
    }

    // ------------------------------------------------------------------ default assistant setup

    private boolean isDefaultAssistant() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            return rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT);
        }
        try {
            String a = Settings.Secure.getString(getContentResolver(), "assistant");
            return a != null && a.startsWith(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    private void updateSetupChip() {
        if (setupChip != null) setupChip.setVisibility(isDefaultAssistant() ? View.GONE : View.VISIBLE);
    }

    private void openAssistantSettings() {
        addBubble("In the screen that opens, choose \"Digital assistant app\" (or \"Device assistance app\") and pick Voice Agent. "
                + "Then swipe up from a bottom corner (gesture navigation) or long-press Home (button navigation) to call me.", false);
        String[] actions = {Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ listening

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("I need the microphone to hear you");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addBubble("Speech recognition isn't available. Install or update the \"Speech Recognition & Synthesis\" "
                    + "or \"Google\" app from the Play Store, then try again. You can type in the meantime.", false);
            setIdle("Voice unavailable — tap ⌨ to type");
            return;
        }
        if (tts != null) tts.stop();
        listenAfterSpeech = false;
        closeAfterSpeech = false;
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        Locale loc = Locale.getDefault();
        String tag = "en".equals(loc.getLanguage()) ? loc.toLanguageTag() : "en-US";
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        live.setText("");
        status.setText("Listening…");
        orb.setState(OrbView.State.LISTENING);
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            setIdle("Couldn't start the microphone — tap to retry");
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { status.setText("Listening…"); busyRetries = 0; }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { orb.setRms(rmsdB); }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { orb.setState(OrbView.State.THINKING); status.setText(""); }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) live.setText(r.get(0));
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty() || r.get(0).trim().isEmpty()) {
                setIdle("I didn't catch that — tap to try again");
                return;
            }
            String heard = r.get(0).trim();
            live.setText("");
            status.setText("");
            addBubble(heard, true);
            orb.setState(OrbView.State.THINKING);
            brain.handle(heard);
        }

        @Override
        public void onError(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    live.setText("");
                    setIdle("I didn't catch that — tap to try again");
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    if (busyRetries++ < 2) ui.postDelayed(AssistantActivity.this::startListening, 400);
                    else setIdle("The microphone is busy — tap to retry");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    setIdle("No connection. Download offline speech in Settings to talk without internet.");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    setIdle("Microphone permission is needed");
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    // Happens after we cancel on purpose; stay quiet.
                    if (orb.getState() == OrbView.State.LISTENING) setIdle(null);
                    break;
                default:
                    setIdle("Something went wrong (" + error + ") — tap to retry");
            }
        }
    };

    // ------------------------------------------------------------------ speaking

    private void speak(String text, boolean thenListen) {
        listenAfterSpeech = thenListen;
        if (text == null || text.isEmpty()) {
            afterSpeech();
            return;
        }
        if (!ttsReady) {
            queuedSpeech = text;
            queuedListen = thenListen;
            return;
        }
        // Speak only the first paragraph of long answers (the rest is on screen), and skip symbols.
        String spoken = text.split("\n")[0].replaceAll("[•✦›➤]", "").replace("°C", " degrees").replace("°F", " degrees");
        Bundle params = new Bundle();
        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, "va-" + System.nanoTime());
    }

    private void afterSpeech() {
        if (isFinishing() || isDestroyed()) return;
        if (closeAfterSpeech) {
            finish();
            return;
        }
        if (listenAfterSpeech) {
            listenAfterSpeech = false;
            startListening();
        } else if (orb.getState() != OrbView.State.LISTENING) {
            setIdle(null);
        }
    }

    // ------------------------------------------------------------------ Brain.Host

    @Override
    public Context context() {
        return this;
    }

    @Override
    public void say(String text) {
        addBubble(text, false);
        status.setText("");
        speak(text, false);
    }

    @Override
    public void ask(String text) {
        addBubble(text, false);
        status.setText("");
        speak(text, true);
    }

    @Override
    public boolean launch(Intent intent, String text) {
        if (!launchQuiet(intent)) return false;
        if (text == null || text.isEmpty()) {
            finish();
        } else {
            addBubble(text, false);
            closeAfterSpeech = true;
            speak(text, false);
        }
        return true;
    }

    @Override
    public boolean launchQuiet(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    @Override
    public void thinking() {
        orb.setState(OrbView.State.THINKING);
        status.setText("Thinking…");
    }

    @Override
    public void requestPermissions(String[] permissions, String retry) {
        retryAfterPermission = retry;
        requestPermissions(permissions, REQ_COMMAND);
    }

    @Override
    public void close(String text) {
        if (text == null || text.isEmpty()) {
            finish();
            return;
        }
        addBubble(text, false);
        closeAfterSpeech = true;
        speak(text, false);
    }

    @Override
    public void runOnUi(Runnable r) {
        ui.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else setIdle("Microphone permission is needed to talk — or tap ⌨ to type");
        } else if (requestCode == REQ_COMMAND) {
            String retry = retryAfterPermission;
            retryAfterPermission = null;
            if (retry != null) brain.handle(retry);
            else if (orb.getState() == OrbView.State.THINKING) setIdle(null);
        }
    }
}
