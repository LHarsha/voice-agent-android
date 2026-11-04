package com.voiceagent;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.TelephonyManager;
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
import java.util.List;
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
    private TextView smartChip;

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
        silentRetries = 0;
        engineSwitches = 0;
        brain.newSession();
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
        session++; // ignore late callbacks from the recognizer
        ui.removeCallbacks(listenRunnable);
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

        smartChip = new TextView(this);
        smartChip.setText("✦  Turn on smart mode (free)  ›");
        smartChip.setTextColor(Color.parseColor("#BFF0FF"));
        smartChip.setTextSize(13);
        smartChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Color.parseColor("#2233D1FF"));
        sb.setStroke(dp(1), Color.parseColor("#6633D1FF"));
        sb.setCornerRadius(dp(18));
        smartChip.setBackground(sb);
        smartChip.setOnClickListener(v -> showSmartSettings());
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.gravity = Gravity.CENTER_HORIZONTAL;
        sclp.bottomMargin = dp(8);
        panel.addView(smartChip, sclp);

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
            if (orb.getState() == OrbView.State.LISTENING && recognizer != null) {
                recognizer.stopListening();
            } else {
                silentRetries = 0;
                engineSwitches = 0;
                startListening();
            }
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
        TextView gear = roundButton("⚙");
        gear.setContentDescription("Smart mode settings");
        gear.setOnClickListener(v -> showSmartSettings());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END | Gravity.BOTTOM);
        orbRow.addView(gear, glp);
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
        session++;
        ui.removeCallbacks(listenRunnable);
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
        if (smartChip != null) smartChip.setVisibility(Gemini.enabled(this) ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ smart mode settings

    private void showSmartSettings() {
        session++;
        ui.removeCallbacks(listenRunnable);
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
        setIdle(null);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        TextView info = new TextView(this);
        info.setText("Smart mode uses Google's Gemini AI to understand what you say, so you can talk naturally "
                + "and ask anything. It's free: you only need a free API key.\n\n"
                + "1. Tap \"Get free key\" and sign in with your Google account.\n"
                + "2. Tap \"Create API key\", then copy it.\n"
                + "3. Come back, paste it below, and tap Save.\n\n"
                + "Privacy: what you say is sent to Google to be understood. On the free tier Google may use it to "
                + "improve its products. Your contacts and notes stay on the phone. Simple commands like the time "
                + "or the flashlight never leave the phone.");
        info.setTextSize(14);
        box.addView(info);
        EditText keyInput = new EditText(this);
        keyInput.setHint("Paste your Gemini API key");
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setText(Gemini.key(this));
        box.addView(keyInput);

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Smart mode")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String k = keyInput.getText().toString().replaceAll("\\s+", "");
                    Gemini.setKey(this, k);
                    brain.newSession();
                    updateSetupChip();
                    if (k.isEmpty()) {
                        say("Smart mode is off. I'll use offline mode.");
                    } else {
                        testSmartMode();
                    }
                })
                .setNeutralButton("Get free key", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.show();
    }

    /** One quick request so the user knows right away whether the key works. */
    private void testSmartMode() {
        thinking();
        Gemini g = new Gemini(this);
        new Thread(() -> {
            String msg;
            try {
                org.json.JSONObject r = g.understand("Say a one-line hello confirming smart mode works.");
                String sayText = r.optString("say", "").trim();
                msg = "Smart mode is on. " + (sayText.isEmpty() ? "" : sayText);
            } catch (Gemini.KeyException e) {
                msg = "That key didn't work. Copy it again from Google AI Studio and paste the whole key.";
            } catch (Gemini.RateLimitException e) {
                msg = "The key works, but the free limit is used up for now. Try again in a minute.";
            } catch (Exception e) {
                msg = "I couldn't reach Gemini. Check your internet connection. (" + e.getMessage() + ")";
            }
            String m = msg;
            runOnUi(() -> say(m));
        }).start();
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

    /** Speech engines to try, best first. A ComponentName, DEFAULT_ENGINE or ON_DEVICE_ENGINE. */
    private static final String DEFAULT_ENGINE = "default";
    private static final String ON_DEVICE_ENGINE = "on-device";
    private final List<Object> engines = new ArrayList<>();
    private int engineIndex;
    private Object recognizerEngine;
    private int session;
    private String lastPartial;
    private boolean heardSpeech;
    private int silentRetries;
    private int engineSwitches;
    private static volatile ArrayList<String> biasWords;

    private void buildEngineList() {
        engines.clear();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        // Google's recognizer is far more accurate than most phone defaults (often Bixby on Samsung).
        String[] preferred = {"com.google.android.googlequicksearchbox", "com.google.android.tts"};
        for (String pkg : preferred) {
            for (ResolveInfo ri : services) {
                if (ri.serviceInfo != null && pkg.equals(ri.serviceInfo.packageName)) {
                    engines.add(new ComponentName(pkg, ri.serviceInfo.name));
                    break;
                }
            }
        }
        engines.add(DEFAULT_ENGINE);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) engines.add(ON_DEVICE_ENGINE);
            } catch (RuntimeException ignored) {
            }
        }
        // Start with whichever engine worked last time.
        String last = getSharedPreferences("voice", MODE_PRIVATE).getString("engine", null);
        engineIndex = 0;
        for (int i = 0; i < engines.size(); i++) {
            if (engineKey(engines.get(i)).equals(last)) engineIndex = i;
        }
    }

    private static String engineKey(Object e) {
        return e instanceof ComponentName ? ((ComponentName) e).flattenToString() : String.valueOf(e);
    }

    private SpeechRecognizer createRecognizer(Object engine) {
        if (engine instanceof ComponentName) return SpeechRecognizer.createSpeechRecognizer(this, (ComponentName) engine);
        if (ON_DEVICE_ENGINE.equals(engine) && Build.VERSION.SDK_INT >= 31) return SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        return SpeechRecognizer.createSpeechRecognizer(this);
    }

    /** Indian English when the phone is in India: much better with Indian accents and names. */
    private String recognitionLanguage() {
        Locale loc = Locale.getDefault();
        String sim = "";
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && tm.getSimCountryIso() != null) sim = tm.getSimCountryIso().toUpperCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
        }
        boolean india = "IN".equals(sim) || "IN".equals(loc.getCountry());
        if ("en".equals(loc.getLanguage())) return india ? "en-IN" : loc.toLanguageTag();
        return india ? "en-IN" : "en-US";
    }

    /** Words to nudge the recognizer toward: command words plus your contacts' and apps' names. */
    private void loadBiasWords() {
        if (biasWords != null || Build.VERSION.SDK_INT < 33) return;
        Context app = getApplicationContext();
        new Thread(() -> {
            java.util.LinkedHashSet<String> words = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "call", "text", "message", "WhatsApp", "alarm", "timer", "remind me", "reminder", "weather",
                    "open", "play", "pause", "flashlight", "torch", "navigate", "directions", "volume", "brightness",
                    "Bluetooth", "Wi-Fi", "do not disturb", "selfie", "note", "calendar", "translate"));
            try {
                if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    try (android.database.Cursor c = app.getContentResolver().query(
                            android.provider.ContactsContract.Contacts.CONTENT_URI,
                            new String[]{android.provider.ContactsContract.Contacts.DISPLAY_NAME},
                            android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER + "=1", null,
                            android.provider.ContactsContract.Contacts.STARRED + " DESC")) {
                        while (c != null && c.moveToNext() && words.size() < 75) {
                            String n = c.getString(0);
                            if (n != null && n.length() <= 40) words.add(n);
                        }
                    }
                }
                PackageManager pm = app.getPackageManager();
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                    if (words.size() >= 100) break;
                    words.add(String.valueOf(ri.loadLabel(pm)));
                }
            } catch (RuntimeException ignored) {
            }
            biasWords = new ArrayList<>(words);
        }).start();
    }

    private void startListening() {
        ui.removeCallbacks(listenRunnable);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("I need the microphone to hear you");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (engines.isEmpty()) buildEngineList();
        if (!SpeechRecognizer.isRecognitionAvailable(this) && engines.size() <= 1) {
            addBubble("Speech recognition isn't available. Install or update the \"Google\" app or "
                    + "\"Speech Recognition & Synthesis\" from the Play Store, then try again. You can type in the meantime.", false);
            setIdle("Voice unavailable — tap ⌨ to type");
            return;
        }
        loadBiasWords();
        if (tts != null) tts.stop();
        listenAfterSpeech = false;
        closeAfterSpeech = false;
        lastPartial = null;
        heardSpeech = false;

        Object engine = engines.get(Math.min(engineIndex, engines.size() - 1));
        if (recognizer == null || !engine.equals(recognizerEngine)) {
            if (recognizer != null) recognizer.destroy();
            try {
                recognizer = createRecognizer(engine);
            } catch (RuntimeException e) {
                recognizer = null;
            }
            recognizerEngine = engine;
        } else {
            recognizer.cancel();
        }
        if (recognizer == null) {
            if (switchEngine()) return;
            setIdle("Couldn't start speech recognition — tap ⌨ to type");
            return;
        }
        final int mySession = ++session;
        recognizer.setRecognitionListener(new Listener(mySession));

        String lang = recognitionLanguage();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        // Give people time to finish their sentence instead of cutting them off at the first pause.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);
        if (Build.VERSION.SDK_INT >= 33 && biasWords != null) {
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasWords);
        }
        live.setText("");
        status.setText(silentRetries > 0 ? "Still listening…" : "Listening…");
        orb.setState(OrbView.State.LISTENING);
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            if (!switchEngine()) setIdle("Couldn't start the microphone — tap to retry");
        }
    }

    private final Runnable listenRunnable = this::startListening;

    /** Move to the next speech engine and try again. Returns false when there are none left. */
    private boolean switchEngine() {
        if (engineSwitches >= engines.size() - 1) return false;
        engineSwitches++;
        engineIndex = (engineIndex + 1) % engines.size();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        status.setText("Switching speech engine…");
        ui.postDelayed(listenRunnable, 250);
        return true;
    }

    /** Act on what was heard: the best of the recognizer's guesses. */
    private void process(List<String> guesses) {
        String best = null;
        int bestScore = -1;
        for (String g : guesses) {
            if (g == null || g.trim().isEmpty()) continue;
            int sc = Brain.score(g);
            if (sc > bestScore) { // earlier guesses win ties: they're the recognizer's own favourites
                bestScore = sc;
                best = g.trim();
            }
        }
        if (best == null) {
            setIdle("I didn't catch that — tap to try again");
            return;
        }
        silentRetries = 0;
        engineSwitches = 0;
        getSharedPreferences("voice", MODE_PRIVATE).edit()
                .putString("engine", engineKey(engines.get(Math.min(engineIndex, engines.size() - 1)))).apply();
        live.setText("");
        status.setText("");
        addBubble(best, true);
        orb.setState(OrbView.State.THINKING);
        brain.handle(best);
    }

    private final class Listener implements RecognitionListener {
        private final int id;

        Listener(int id) { this.id = id; }

        private boolean stale() { return id != session || isFinishing() || isDestroyed(); }

        @Override public void onReadyForSpeech(Bundle params) { if (!stale()) { status.setText(silentRetries > 0 ? "Still listening…" : "Listening…"); busyRetries = 0; } }
        @Override public void onBeginningOfSpeech() { heardSpeech = true; }
        @Override public void onRmsChanged(float rmsdB) { if (!stale()) orb.setRms(rmsdB); }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { if (!stale()) { orb.setState(OrbView.State.THINKING); status.setText(""); } }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            if (stale()) return;
            ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && r.get(0) != null && !r.get(0).trim().isEmpty()) {
                lastPartial = r.get(0).trim();
                live.setText(lastPartial);
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (stale()) return;
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            List<String> guesses = new ArrayList<>();
            if (r != null) guesses.addAll(r);
            if (lastPartial != null) guesses.add(lastPartial);
            boolean empty = true;
            for (String g : guesses) if (g != null && !g.trim().isEmpty()) empty = false;
            if (empty) {
                onError(SpeechRecognizer.ERROR_NO_MATCH);
                return;
            }
            process(guesses);
        }

        @Override
        public void onError(int error) {
            if (stale()) return;
            // Many recognizers report an error even though they already heard you: use that text.
            if (lastPartial != null && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                List<String> g = new ArrayList<>();
                g.add(lastPartial);
                process(g);
                return;
            }
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    live.setText("");
                    if (silentRetries < 1 && !heardSpeech) {
                        silentRetries++;
                        ui.postDelayed(listenRunnable, 150);
                    } else {
                        silentRetries = 0;
                        setIdle("I didn't catch that — tap the orb and try again");
                    }
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    if (busyRetries++ < 2) {
                        if (recognizer != null) {
                            recognizer.destroy();
                            recognizer = null;
                        }
                        ui.postDelayed(listenRunnable, 500);
                    } else if (!switchEngine()) {
                        setIdle("The microphone is busy — tap to retry");
                    }
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    setIdle("Microphone permission is needed");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    if (!switchEngine()) setIdle("No internet for speech. Download offline speech (see the README) or tap ⌨ to type.");
                    break;
                default:
                    // Server errors, unsupported language, engine crashes: try the next engine.
                    if (!switchEngine()) setIdle("Speech recognition failed (" + error + ") — tap to retry or ⌨ to type");
            }
        }
    }

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
            // A short pause so the mic doesn't catch the end of our own voice.
            ui.postDelayed(listenRunnable, 300);
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
    public void notice(String text) {
        addBubble(text, false);
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
