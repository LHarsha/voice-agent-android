package com.voiceagent;

import android.Manifest;
import android.app.NotificationManager;
import android.app.SearchManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands what you said and does it. Everything here is rule-based and runs on
 * the phone; only "who is / what is", definitions and weather use free web APIs.
 */
public class Brain {

    /** What the screen/voice side provides to the brain. */
    public interface Host {
        Context context();

        /** Speak + show an answer. */
        void say(String text);

        /** Speak + show, then listen for the reply. */
        void ask(String text);

        /** Speak + show, start another app, then get out of the way. Returns false if no app handles it. */
        boolean launch(Intent intent, String text);

        /** Start something in the background (e.g. alarm with no UI) without closing. */
        boolean launchQuiet(Intent intent);

        /** Show the spinner while something slow (network) happens. */
        void thinking();

        /** Ask Android for permissions, then call {@link #handle(String)} again with retry. */
        void requestPermissions(String[] permissions, String retry);

        /** Say goodbye and close. */
        void close(String text);

        /** Show a small note on screen without speaking it. */
        void notice(String text);

        void runOnUi(Runnable r);
    }

    private final Host host;
    private final Context ctx;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Set<String> permissionsAsked = new HashSet<>();
    private final Random random = new Random();

    // Follow-up conversation state ("What should it say?")
    private enum Pending { NONE, SMS_BODY, REMINDER_TEXT, REMINDER_TIME, ALARM_TIME, TIMER_LENGTH, NOTE_TEXT }

    private Pending pending = Pending.NONE;
    private String pendingName, pendingNumber, pendingText;
    private boolean pendingWhatsApp;

    public Brain(Host host) {
        this.host = host;
        this.ctx = host.context();
    }

    public void shutdown() {
        bg.shutdownNow();
    }

    // =====================================================================================
    // Entry point
    // =====================================================================================

    public void handle(String raw) {
        if (raw == null) return;
        raw = raw.trim();
        if (raw.isEmpty()) return;
        String s = normalize(raw);
        String light = clean(raw);
        if (s.isEmpty()) s = light;

        if (s.matches("(cancel|stop|never ?mind|nothing|forget it|no thanks?|that's all|that is all|bye|goodbye|good bye|bye bye|dismiss|close|exit|quit|go away|shut up)")) {
            pending = Pending.NONE;
            host.close(s.contains("bye") ? "Bye!" : "Okay.");
            return;
        }

        if (pending != Pending.NONE && handlePending(s, raw)) return;
        pending = Pending.NONE;

        if (ctx != null && Gemini.enabled(ctx) && !fastPath(s)) {
            smart(raw, s, light);
            return;
        }
        local(s, raw, light);
    }

    /** Start a fresh conversation (the assistant was opened again). */
    public void newSession() {
        pending = Pending.NONE;
        if (gemini != null) gemini.clearHistory();
    }

    /** The offline, rule-based understanding. */
    private void local(String s, String raw, String light) {
        if (smallTalk(light) || smallTalk(s)) return;
        if (timeAndDate(s)) return;
        if (!s.startsWith("play ") && device(s)) return;
        if (media(s)) return;
        if (alarmsAndTimers(s)) return;
        if (reminders(s, raw)) return;
        if (calendar(s, raw)) return;
        if (notes(s, raw)) return;
        if (communication(s, raw)) return;
        if (navigation(s)) return;
        if (camera(s)) return;
        if (fun(s)) return;
        if (translate(s, raw)) return;
        if (weather(s)) return;
        if (conversions(s)) return;
        if (openApp(s)) return;
        if (math(s)) return;
        if (knowledge(s)) return;
        webSearch(raw, "Here's what I found on the web.");
    }

    /** Lowercase + strip punctuation the recognizer adds ("Okay, call Mom." -> "okay call mom"). */
    static String clean(String raw) {
        String s = raw.toLowerCase(Locale.ROOT)
                .replace("’", "'").replace("‘", "'")
                .replaceAll("[“”\"]", "")
                .replace("wi-fi", "wifi").replace("wi fi", "wifi")
                .trim();
        s = TimeParser.normalize(s);
        // Keep "7:30" and "2.5" but drop sentence punctuation everywhere else.
        s = s.replaceAll("(?<!\\d)[.:]|[.:](?!\\d)", " ");
        s = s.replaceAll("[,;!?¿¡…()\\[\\]]", " ");
        s = s.replaceAll("(?<=\\s|^)-+|-+(?=\\s|$)", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static final String LEADING_FILLER = "^(?:um+|uh+m*|erm|hmm+|so|well|okay|ok|alright|all right|hey|yeah|yes|please|kindly"
            + "|can you|could you|would you|will you|can u|could u|would you mind|do you mind|i want you to|i'd like you to"
            + "|i would like you to|i need you to|i want to|i wanna|i would like to|i'd like to|i need to|i have to|i gotta"
            + "|go ahead and|just|quickly|quick|now|let's|lets|help me|try to|try and|and|also|then)\\s+";
    private static final String TRAILING_FILLER = "\\s+(?:please|for me|right now|now|real quick|quickly|thanks|thank you"
            + "|okay|ok|immediately|asap|right away|will you|can you|could you|if you can|for me please)$";

    static String normalize(String raw) {
        String s = clean(raw);
        // Wake words people say out of habit: "hey google", "ok siri", "alexa", ...
        s = s.replaceAll("^(?:(?:hey|hi|ok|okay|yo|hello)\\s+)?(?:google|siri|alexa|bixby|assistant|agent|voice agent|jarvis)\\b\\s*", "");
        String prev;
        do {
            prev = s;
            if (s.contains(" ")) s = s.replaceAll(LEADING_FILLER, "");
            s = s.replaceAll(TRAILING_FILLER, "").trim();
        } while (!s.equals(prev));
        return canonical(s);
    }

    /** Rewrite common alternative phrasings into the forms the handlers understand. */
    static String canonical(String s) {
        String[][] rules = {
                // calls
                {"^(?:make|place|start|do) (?:a |an )?(?:phone |voice )?call (?:to|with) (.+)$", "call $1"},
                {"^(?:make|place|start) (?:a |an )?video call (?:to|with) (.+)$", "video call $1"},
                {"^give (.+?) a (?:call|ring|buzz)$", "call $1"},
                {"^(?:call|dial|phone|ring) (?:up )?to (.+)$", "call $1"},
                {"^(?:talk|speak) (?:to|with) (.+)$", "call $1"},
                {"^(?:get|put) me through to (.+)$", "call $1"},
                // messages
                {"^send (?:a |an )?(?:text|message|sms|msg)(?: message)? (?:to )?(.+?) on whats ?app(.*)$", "whatsapp $1$2"},
                {"^send (.+?) (?:a |an )?whats ?app(?: message)?(?: (?:saying|that says|that|to say) (.+))?$", "whatsapp $1 saying $2"},
                {"^send (.+?) (?:a |an )?(?:text|message|sms|msg)(?: message)?(?: (?:saying|that says|that|to say) (.+))?$", "text $1 saying $2"},
                {"^(?:write|compose|draft) (?:a |an )?(?:text|message|sms)(?: message)? (?:to|for) (.+)$", "text $1"},
                {"^(?:send|write) (?:a |an )?(?:msg|message|text) (?:to )?(.+)$", "text $1"},
                // reminders
                {"^(?:don't|do not) let me forget (?:to |about )?(.+)$", "remind me to $1"},
                {"^(?:can you |please )?remind me (?:that i need |that i have |i need |i have )to (.+)$", "remind me to $1"},
                {"^(?:make sure i|help me remember to) (.+)$", "remind me to $1"},
                // apps / camera
                {"^open up (.+)$", "open $1"},
                {"^(open|launch|start) (?:the |my )?(.+?) (?:app|application)$", "$1 $2"},
                // alarms / timers
                {"^(?:put|create|add|make) (?:an |a )?alarm(.*)$", "set an alarm$1"},
                {"^(?:put|create|add|make) (?:a )?timer(.*)$", "set a timer$1"},
                {"^(\\d+) (minutes?|mins?|hours?|seconds?|secs?) timer$", "set a timer for $1 $2"},
                {"^set (?:a |an )?(\\d+) (minutes?|mins?|hours?|seconds?|secs?) timer$", "set a timer for $1 $2"},
                // music
                {"^play (?:me )?(?:some|any|my)? ?(?:songs|music|song)$", "play music"},
                {"^play (?:me )?(?:some |a )?(?:songs?|music) (?:by|from|of) (.+)$", "play $1"},
                {"^play (.+?) (?:songs|song|music)$", "play $1"},
                {"^(?:i want to |let's )?listen to (?:some )?music$", "play music"},
        };
        for (String[] r : rules) {
            if (s.matches(r[0])) {
                s = s.replaceAll(r[0], r[1]).replaceAll("\\s+saying\\s*$", "").trim();
                break;
            }
        }
        return s;
    }

    /**
     * How strongly a recognizer guess looks like something we can act on. Used to choose the
     * best of the recognizer's alternatives ("call mom" beats "colour mom").
     */
    public static int score(String raw) {
        String s = normalize(raw);
        if (s.isEmpty()) return 0;
        if (s.matches("^(call|dial|phone|video call|text|message|sms|whatsapp|send|open|launch|play|pause|resume|next|previous|skip"
                + "|set|wake|remind|navigate|directions|take me|turn|switch|increase|decrease|raise|lower|mute|unmute|volume|brightness"
                + "|translate|define|convert|flip|roll|note|take a|read|show|find|search|tell me)\\b.*")) return 4;
        if (s.matches(".*\\b(alarm|timer|remind|weather|forecast|flashlight|torch|volume|bluetooth|wifi|battery|calendar|notes?)\\b.*")) return 3;
        if (MathEval.tryAnswer(s) != null || Conversions.units(s) != null) return 3;
        if (s.matches("^(who|what|what's|when|where|why|how|which|is|are|do|does|can|did|will|tell)\\b.*")) return 2;
        return 1;
    }

    // =====================================================================================
    // Smart mode (Gemini)
    // =====================================================================================

    private Gemini gemini;
    private boolean warnedSmart;

    /** Simple, unambiguous commands are handled instantly on the phone, even in smart mode. */
    private boolean fastPath(String s) {
        if (s.matches("(what('s| is) the )?(current )?time( is it)?( right now| now)?|what time is it|tell me the time"
                + "|(what('s| is) )?(the |today's |todays )?date( today)?|what day is (it|today)( today)?")) return timeAndDate(s);
        if (s.matches("(pause|stop|resume|continue|unpause|next|skip|previous|prev)( the| this| my)?( music| song| track| video| playback)?"
                + "|play( the)? (next|previous)( song| track)?|play( some| my)? music")) return media(s);
        if (s.matches("(turn|switch) (on|off) (the )?(flash ?light|torch)|(flash ?light|torch) (on|off)")) return device(s);
        if (s.matches("(turn |set )?(the )?volume (up|down)|(increase|decrease|raise|lower) (the )?volume|mute|unmute")) return device(s);
        if (s.matches("(what's |what is )?(my )?battery( level| percentage| status)?")) return device(s);
        if (s.matches(".*\\btimer\\b.*") && TimeParser.parseDurationSeconds(s) > 0 && !s.matches(".*\\b(cancel|stop|delete)\\b.*")) {
            return alarmsAndTimers(s);
        }
        if (MathEval.tryAnswer(s) != null) return math(s);
        if (Conversions.units(s) != null) return conversions(s);
        return false;
    }

    private void smart(String raw, String s, String light) {
        if (gemini == null) gemini = new Gemini(ctx);
        host.thinking();
        bg.execute(() -> {
            org.json.JSONObject r = null;
            Exception err = null;
            try {
                r = gemini.understand(raw);
            } catch (Exception e) {
                err = e;
            }
            final org.json.JSONObject result = r;
            final Exception error = err;
            host.runOnUi(() -> {
                if (result != null) {
                    try {
                        perform(result, raw);
                        return;
                    } catch (RuntimeException e) {
                        // fall through to the offline engine
                    }
                } else if (!warnedSmart) {
                    warnedSmart = true;
                    if (error instanceof Gemini.KeyException) {
                        host.notice("Smart mode: your Gemini key isn't working, so I'm using offline mode. Tap ⚙ to check it.");
                    } else if (error instanceof Gemini.RateLimitException) {
                        host.notice("Smart mode: the free Gemini limit is used up for now, so I'm using offline mode.");
                    } else {
                        String why = error == null || error.getMessage() == null ? "" : " (" + error.getMessage() + ")";
                        host.notice("Smart mode didn't respond, so I'm using offline mode." + why);
                    }
                }
                local(s, raw, light);
            });
        });
    }

    /** Carry out the action Gemini chose. */
    private void perform(org.json.JSONObject r, String raw) {
        String action = r.optString("action", "answer").trim().toLowerCase(Locale.ROOT);
        org.json.JSONObject a = r.optJSONObject("args");
        if (a == null) a = new org.json.JSONObject();
        String say = r.optString("say", "").trim();
        boolean expect = r.optBoolean("expect_reply", false);
        String who = a.optString("contact", "").trim();
        String number = a.optString("number", "").replaceAll("[^0-9+]", "");

        switch (action) {
            case "call":
                callTo(who, number, raw, false);
                return;
            case "video_call":
                callTo(who, number, raw, true);
                return;
            case "sms":
            case "whatsapp":
                messageTo(who, number, a.optString("message", "").trim(), action.equals("whatsapp"), raw);
                return;
            case "email": {
                String to = a.optString("to", "");
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (to.contains("@") ? to : "")));
                if (!a.optString("subject").isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"));
                if (!a.optString("body").isEmpty()) i.putExtra(Intent.EXTRA_TEXT, a.optString("body"));
                host.launch(i, "Here's your email.");
                return;
            }
            case "open_app": {
                String name = a.optString("name", "").trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty() || !openApp("open " + name)) host.ask("Which app should I open?");
                return;
            }
            case "play_music": {
                String q = a.optString("query", "").trim();
                String app = a.optString("app", "").trim().toLowerCase(Locale.ROOT);
                if (q.isEmpty()) {
                    if (!playAnything()) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "");
                } else {
                    playMusic(q, app.isEmpty() ? null : app);
                }
                return;
            }
            case "media": {
                String c = a.optString("command", "resume");
                int key = c.startsWith("pause") || c.startsWith("stop") ? KeyEvent.KEYCODE_MEDIA_PAUSE
                        : c.startsWith("next") ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : c.startsWith("prev") ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY;
                mediaKey(key, key == KeyEvent.KEYCODE_MEDIA_PAUSE ? "Paused." : "");
                return;
            }
            case "set_alarm": {
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, a.optInt("hour", 7));
                c.set(Calendar.MINUTE, a.optInt("minute", 0));
                c.set(Calendar.SECOND, 0);
                Calendar date = parseDate(a.optString("date", ""));
                if (date != null) {
                    c.set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH));
                } else if (!c.after(Calendar.getInstance())) {
                    c.add(Calendar.DAY_OF_MONTH, 1);
                }
                String label = a.optString("label", "").trim();
                setAlarm(c, label.isEmpty() ? null : label);
                return;
            }
            case "set_timer": {
                int sec = a.optInt("seconds", 0);
                if (sec <= 0) {
                    pending = Pending.TIMER_LENGTH;
                    host.ask("For how long?");
                } else {
                    setTimer(sec);
                }
                return;
            }
            case "show_alarms":
                host.launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS), "Here are your alarms.");
                return;
            case "show_timers":
                host.launch(new Intent(AlarmClock.ACTION_SHOW_TIMERS), "Here are your timers.");
                return;
            case "reminder": {
                String text = a.optString("text", "").trim();
                Calendar at = parseDateTime(a.optString("at", ""));
                if (text.isEmpty()) {
                    pendingReminderTime = at;
                    pending = Pending.REMINDER_TEXT;
                    host.ask("What should I remind you about?");
                } else if (at == null) {
                    pendingText = text;
                    pending = Pending.REMINDER_TIME;
                    host.ask("When should I remind you?");
                } else {
                    if (!at.after(Calendar.getInstance())) at.add(Calendar.DAY_OF_MONTH, 1);
                    scheduleReminder(cleanSentence(text), at);
                }
                return;
            }
            case "calendar_add": {
                String title = a.optString("title", "Event");
                Calendar start = parseDateTime(a.optString("start", ""));
                Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title);
                if (start != null) {
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.getTimeInMillis());
                    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.getTimeInMillis() + a.optInt("minutes", 60) * 60_000L);
                }
                host.launch(i, "Here's your event" + (start != null ? " for " + TimeParser.speakWhen(start) : "") + ". Tap save to confirm.");
                return;
            }
            case "calendar_read": {
                Perm p = ensure(raw, Manifest.permission.READ_CALENDAR);
                if (p == Perm.REQUESTED) return;
                if (p == Perm.DENIED) {
                    host.say("I need calendar access to read your schedule.");
                    return;
                }
                readCalendar("tomorrow".equals(a.optString("day")) ? 1 : 0);
                return;
            }
            case "note_add":
                saveNote(cleanSentence(a.optString("text", "")));
                return;
            case "notes_read":
                notes("read my notes", raw);
                return;
            case "navigate": {
                String dest = a.optString("destination", "").trim();
                String mode = a.optString("mode", "");
                String m = mode.startsWith("walk") ? "&mode=w" : mode.startsWith("bike") ? "&mode=b" : "";
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + m));
                if (!host.launch(i, "Getting directions to " + dest + ".")) {
                    host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "Here's " + dest + " on the map.");
                }
                return;
            }
            case "map_search": {
                String q = a.optString("query", "");
                host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))), "Here are " + q + " nearby.");
                return;
            }
            case "weather": {
                String place = a.optString("place", "").trim();
                boolean tomorrow = "tomorrow".equals(a.optString("day"));
                weather("weather" + (place.isEmpty() ? "" : " in " + place.toLowerCase(Locale.ROOT)) + (tomorrow ? " tomorrow" : ""));
                return;
            }
            case "time_in":
                timeIn(a.optString("place", ""));
                return;
            case "flashlight":
                setTorch(a.optBoolean("on", true));
                return;
            case "volume": {
                String change = a.optString("change", "");
                volume(a.has("level") ? "set volume to " + a.optInt("level") + " percent"
                        : change.equals("max") ? "max volume" : change.equals("mute") ? "mute" : change.equals("unmute") ? "unmute"
                        : change.equals("down") ? "volume down" : "volume up");
                return;
            }
            case "brightness": {
                String change = a.optString("change", "");
                brightness(a.has("level") ? "brightness " + a.optInt("level") + " percent"
                        : change.equals("auto") ? "auto brightness" : change.equals("down") ? "brightness down" : "brightness up");
                return;
            }
            case "do_not_disturb":
                doNotDisturb(a.optBoolean("on", true));
                return;
            case "ringer": {
                String mode = a.optString("mode", "normal");
                ringer(mode.startsWith("vib") ? "vibrate mode" : mode.startsWith("sil") ? "silent mode" : "sound mode");
                return;
            }
            case "settings": {
                String page = a.optString("page", "main");
                Intent i;
                switch (page) {
                    case "wifi": i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI) : new Intent(Settings.ACTION_WIFI_SETTINGS); break;
                    case "bluetooth": i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS); break;
                    case "mobile_data": i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) : new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS); break;
                    case "airplane": i = new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS); break;
                    case "hotspot": i = new Intent(Settings.ACTION_WIRELESS_SETTINGS); break;
                    case "location": i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS); break;
                    case "nfc": i = new Intent(Settings.ACTION_NFC_SETTINGS); break;
                    case "battery_saver": i = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS); break;
                    case "display": i = new Intent(Settings.ACTION_DISPLAY_SETTINGS); break;
                    case "sound": i = new Intent(Settings.ACTION_SOUND_SETTINGS); break;
                    case "apps": i = new Intent(Settings.ACTION_APPLICATION_SETTINGS); break;
                    default: i = new Intent(Settings.ACTION_SETTINGS);
                }
                host.launch(i, say.isEmpty() ? "Here you go." : say);
                return;
            }
            case "camera": {
                String mode = a.optString("mode", "photo");
                camera(mode.equals("selfie") ? "take a selfie" : mode.equals("video") ? "record a video" : "take a photo");
                return;
            }
            case "translate": {
                String lang = a.optString("language", "").toLowerCase(Locale.ROOT).trim();
                String code = LANGS.get(lang);
                String text = a.optString("text", "");
                String url = "https://translate.google.com/?sl=auto&op=translate&tl=" + (code != null ? code : "en") + "&text=" + Uri.encode(text);
                host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), say.isEmpty() ? "Here's the translation." : say);
                return;
            }
            case "web_search":
                webSearch(a.optString("query", raw), say.isEmpty() ? "Here's what I found on the web." : say);
                return;
            case "battery":
                device("battery");
                return;
            case "answer":
            case "none":
            default:
                if (say.isEmpty()) say = "Sorry, I didn't get that. Could you say it another way?";
                if (expect) host.ask(say);
                else host.say(say);
        }
    }

    private static Calendar parseDateTime(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        String[] fmts = {"yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm"};
        for (String f : fmts) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
                df.setLenient(false);
                Date d = df.parse(iso.length() > 19 ? iso.substring(0, 19) : iso);
                if (d == null) continue;
                Calendar c = Calendar.getInstance();
                c.setTime(d);
                return c;
            } catch (java.text.ParseException ignored) {
            }
        }
        return null;
    }

    private static Calendar parseDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        return parseDateTime(iso.substring(0, 10) + "T00:00");
    }

    /** Call someone by name or number (smart mode). */
    private void callTo(String who, String number, String retry, boolean video) {
        if (!number.isEmpty() && !video) {
            placeCall(number, number);
            return;
        }
        if (who.isEmpty()) {
            host.ask("Who should I call?");
            return;
        }
        Perm p = ensure(retry, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE);
        if (p == Perm.REQUESTED) return;
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            host.say("I need permission to read your contacts to call someone by name.");
            return;
        }
        Contacts.Match c = Contacts.find(ctx, who);
        if (c == null) {
            host.say("I couldn't find " + who + " in your contacts.");
            return;
        }
        if (video) whatsappOrSms(c.name, c.number, "", true);
        else placeCall(c.number, c.name);
    }

    /** Text or WhatsApp someone by name or number (smart mode). */
    private void messageTo(String who, String number, String body, boolean whatsapp, String retry) {
        String name, num;
        if (!number.isEmpty()) {
            name = number;
            num = number;
        } else {
            if (who.isEmpty()) {
                host.ask("Who should I send it to?");
                return;
            }
            Perm p = ensure(retry, Manifest.permission.READ_CONTACTS);
            if (p == Perm.REQUESTED) return;
            if (!granted(Manifest.permission.READ_CONTACTS)) {
                host.say("I need permission to read your contacts to message someone by name.");
                return;
            }
            Contacts.Match c = Contacts.find(ctx, who);
            if (c == null) {
                host.say("I couldn't find " + who + " in your contacts.");
                return;
            }
            name = c.name;
            num = c.number;
        }
        if (body.isEmpty()) {
            pendingName = name;
            pendingNumber = num;
            pendingWhatsApp = whatsapp;
            pending = Pending.SMS_BODY;
            host.ask("What do you want to say to " + name + "?");
            return;
        }
        sendMessage(name, num, body, whatsapp);
    }

    /** Recover original capitalization for a piece of the normalized text (message bodies, notes). */
    private static String original(String raw, String part) {
        if (part.isEmpty()) return part;
        String[] words = part.trim().split("\\s+");
        StringBuilder re = new StringBuilder("(?i)");
        for (int k = 0; k < words.length; k++) {
            if (k > 0) re.append("[\\s,.;:!?\"“”-]+");
            re.append(Pattern.quote(words[k]));
        }
        Matcher m = Pattern.compile(re.toString()).matcher(raw.replace('’', '\''));
        if (m.find()) return raw.substring(m.start(), m.end());
        return Character.toUpperCase(part.charAt(0)) + part.substring(1);
    }

    // =====================================================================================
    // Follow-ups
    // =====================================================================================

    private boolean handlePending(String s, String raw) {
        Pending p = pending;
        pending = Pending.NONE;
        switch (p) {
            case SMS_BODY:
                sendMessage(pendingName, pendingNumber, cleanSentence(raw), pendingWhatsApp);
                return true;
            case NOTE_TEXT:
                saveNote(cleanSentence(raw));
                return true;
            case REMINDER_TEXT: {
                pendingText = cleanSentence(raw);
                if (pendingReminderTime != null) {
                    scheduleReminder(pendingText, pendingReminderTime);
                    pendingReminderTime = null;
                    return true;
                }
                pending = Pending.REMINDER_TIME;
                host.ask("When should I remind you?");
                return true;
            }
            case REMINDER_TIME: {
                Calendar when = whenFrom(s);
                if (when == null) {
                    if (score(raw) >= 3) return false;
                    pending = Pending.REMINDER_TIME;
                    host.ask("Sorry, when? You can say something like \"in 20 minutes\" or \"tomorrow at 9\".");
                    return true;
                }
                scheduleReminder(pendingText, when);
                return true;
            }
            case ALARM_TIME: {
                int[] clock = TimeParser.parseClock(s.matches("\\d.*") ? "at " + s : s);
                if (clock == null) {
                    if (score(raw) >= 3) return false; // a new command, not an answer
                    pending = Pending.ALARM_TIME;
                    host.ask("What time? For example, \"6:30 AM\".");
                    return true;
                }
                setAlarm(TimeParser.nextOccurrence(clock, s), null);
                return true;
            }
            case TIMER_LENGTH: {
                int sec = TimeParser.parseDurationSeconds(s);
                if (sec <= 0) {
                    if (score(raw) >= 3) return false; // a new command, not an answer
                    pending = Pending.TIMER_LENGTH;
                    host.ask("For how long? For example, \"10 minutes\".");
                    return true;
                }
                setTimer(sec);
                return true;
            }
            default:
                return false;
        }
    }

    private static String cleanSentence(String raw) {
        String t = raw.trim().replaceAll("^(saying|say|that|to say|tell (him|her|them))\\s+", "");
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // =====================================================================================
    // Small talk
    // =====================================================================================

    private boolean smallTalk(String s) {
        if (s.matches("(hi|hello|hey|hey there|hello there|yo|hiya|good (morning|afternoon|evening))")) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String g = h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
            host.ask(g + "! What can I do for you?");
            return true;
        }
        if (s.matches("(thanks|thank you|thx|thank u|cheers)\\b.*|(awesome|great|perfect|cool|nice)( thanks| thank you)?")) {
            host.say(pick("You're welcome!", "Happy to help.", "Anytime!"));
            return true;
        }
        if (s.matches(".*\\b(who are you|what('s| is) your name|what are you|introduce yourself)\\b.*")) {
            host.say("I'm your Voice Agent — a free assistant that lives on your phone. Say \"what can you do\" to hear some ideas.");
            return true;
        }
        if (s.matches("how are you.*|how('s| is) it going|how are things|what's up|whats up|sup")) {
            host.say(pick("I'm doing great, thanks for asking! How can I help?", "All good here. What do you need?"));
            return true;
        }
        if (s.matches("(help|what can you do|what can i (say|ask)|what do you do|commands|show me what you can do|how do i use you).*")) {
            host.say("Here are a few things you can try:\n"
                    + "• \"Call Mom\" or \"Text Alex I'm running late\"\n"
                    + "• \"WhatsApp Priya see you soon\"\n"
                    + "• \"Wake me up at 6:30\" or \"Set a timer for 10 minutes\"\n"
                    + "• \"Remind me to take my pills in 2 hours\"\n"
                    + "• \"What's on my calendar tomorrow?\"\n"
                    + "• \"Open YouTube\" or \"Play lo-fi music\"\n"
                    + "• \"Turn on the flashlight\", \"Volume up\", \"Do not disturb on\"\n"
                    + "• \"Navigate to the nearest petrol pump\"\n"
                    + "• \"What's the weather tomorrow?\"\n"
                    + "• \"Who is Sundar Pichai?\" or \"Define serendipity\"\n"
                    + "• \"What's 18 percent of 2450?\"\n"
                    + "• \"Take a note buy milk\" or \"Read my notes\"\n"
                    + "• \"Translate good morning to Hindi\"\n"
                    + "• \"Tell me a joke\" or \"Flip a coin\"");
            return true;
        }
        if (s.matches("(i love you|you're (the best|awesome|smart|great)|good job|well done).*")) {
            host.say(pick("Aw, thank you!", "You're pretty great yourself.", "That made my day."));
            return true;
        }
        if (s.matches(".*\\b(are you (siri|google|alexa|bixby))\\b.*")) {
            host.say("No, I'm your own Voice Agent. Free, private, and all yours.");
            return true;
        }
        return false;
    }

    // =====================================================================================
    // Time & date
    // =====================================================================================

    private boolean timeAndDate(String s) {
        Matcher tz = Pattern.compile("^(?:what(?:'s| is) the )?(?:current )?time(?: is it)?(?: right now| now)? in (.+)$|^what time is it (?:right now |now )?in (.+)$")
                .matcher(s);
        if (tz.find()) {
            String place = tz.group(1) != null ? tz.group(1) : tz.group(2);
            timeIn(place);
            return true;
        }
        if (s.matches("(what('s| is) the )?(current )?time( is it)?( right now| now)?|what time is it.*|tell me the time|time( now)?|do you (know|have) the time")) {
            Calendar c = Calendar.getInstance();
            host.say("It's " + TimeParser.speakTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) + ".");
            return true;
        }
        if (s.matches("(what('s| is) )?(the |today's |todays )?date( today)?|what day is (it|today)( today)?|what's today|what is today|today's date|which day is (it|today)")) {
            String d = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(new Date());
            host.say("Today is " + d + ".");
            return true;
        }
        if (s.matches("what('s| is) tomorrow|what day is tomorrow|tomorrow's date")) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_MONTH, 1);
            host.say("Tomorrow is " + new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(c.getTime()) + ".");
            return true;
        }
        Matcher until = Pattern.compile("how many days (?:until|till|to|left until) (.+)").matcher(s);
        if (until.find()) {
            Calendar target = holiday(until.group(1));
            if (target != null) {
                long days = (target.getTimeInMillis() - System.currentTimeMillis()) / 86_400_000L + 1;
                host.say(days + (days == 1 ? " day" : " days") + " until " + until.group(1) + ".");
                return true;
            }
        }
        return false;
    }

    private Calendar holiday(String name) {
        Calendar now = Calendar.getInstance();
        int month, day;
        if (name.contains("christmas")) { month = Calendar.DECEMBER; day = 25; }
        else if (name.contains("new year")) { month = Calendar.JANUARY; day = 1; }
        else if (name.contains("valentine")) { month = Calendar.FEBRUARY; day = 14; }
        else if (name.contains("halloween")) { month = Calendar.OCTOBER; day = 31; }
        else return null;
        Calendar c = Calendar.getInstance();
        c.set(now.get(Calendar.YEAR), month, day, 0, 0, 0);
        if (c.before(now)) c.add(Calendar.YEAR, 1);
        return c;
    }

    private void timeIn(String place) {
        host.thinking();
        bg.execute(() -> {
            String answer;
            try {
                org.json.JSONObject o = new org.json.JSONObject(WebSkills.get(
                        "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + WebSkills.enc(place)));
                JSONArray r = o.optJSONArray("results");
                if (r == null || r.length() == 0) {
                    answer = "I couldn't find a place called " + place + ".";
                } else {
                    org.json.JSONObject p = r.getJSONObject(0);
                    TimeZone zone = TimeZone.getTimeZone(p.getString("timezone"));
                    Calendar c = Calendar.getInstance(zone);
                    answer = "It's " + TimeParser.speakTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
                            + " in " + p.getString("name") + ".";
                }
            } catch (Exception e) {
                answer = "I couldn't reach the internet to check that.";
            }
            String a = answer;
            host.runOnUi(() -> host.say(a));
        });
    }

    // =====================================================================================
    // Device controls
    // =====================================================================================

    private boolean device(String s) {
        // Battery
        if (s.matches(".*\\bbattery\\b.*") && !s.contains("setting") && !s.contains("saver")) {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();
            host.say("Your battery is at " + pct + "%" + (charging ? " and charging." : "."));
            return true;
        }

        // Flashlight
        if ((s.matches(".*\\b(flash ?light|torch)\\b.*") || s.matches(".*\\b(turn|switch) (on|off) (the )?flash\\b.*|^flash (on|off)$"))
                && !s.contains("camera")) {
            boolean off = s.matches(".*\\b(off|disable|stop|kill)\\b.*");
            setTorch(!off);
            return true;
        }

        // Volume
        if (s.matches(".*\\b(volume|louder|quieter|softer|mute|unmute)\\b.*") || s.matches("turn (it )?(up|down)( the music| the sound)?")) {
            volume(s);
            return true;
        }

        // Brightness
        if (s.matches(".*\\b(brightness|brighter|dimmer|dim the screen)\\b.*")) {
            brightness(s);
            return true;
        }

        // Do not disturb / ringer
        if (s.matches(".*\\b(do not disturb|don't disturb|dnd)\\b.*")) {
            boolean off = s.matches(".*\\b(off|disable|stop|end|deactivate)\\b.*");
            doNotDisturb(!off);
            return true;
        }
        if (s.matches(".*\\b(silent|vibrate|ringer|sound) mode\\b.*|^(silence|vibrate) (my )?phone$|^put (my )?phone on (silent|vibrate)$|^(turn|switch) (on|off) (the )?ringer$")) {
            ringer(s);
            return true;
        }

        // Connectivity: Android doesn't let apps flip these directly, so show the quick panel.
        if (s.matches(".*\\b(wifi|wireless)\\b.*") && !s.matches(".*password.*")) {
            Intent i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI) : new Intent(Settings.ACTION_WIFI_SETTINGS);
            host.launch(i, "Here are your Wi-Fi controls.");
            return true;
        }
        if (s.matches(".*\\bbluetooth\\b.*")) {
            host.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Here's Bluetooth.");
            return true;
        }
        if (s.matches(".*\\b(mobile data|cellular data|internet connection|data connection|turn (on|off) (the )?data)\\b.*")) {
            Intent i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) : new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            host.launch(i, "Here are your internet controls.");
            return true;
        }
        if (s.matches(".*\\b(airplane|aeroplane|flight) mode\\b.*")) {
            host.launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "Here's airplane mode.");
            return true;
        }
        if (s.matches(".*\\bhotspot\\b.*|.*\\btethering\\b.*")) {
            Intent i = new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings");
            if (!host.launch(i, "Here are your hotspot settings.")) {
                host.launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS), "Here are your connection settings.");
            }
            return true;
        }
        if (s.matches(".*\\b(location|gps)\\b.*(on|off|settings|enable|disable).*|^(turn|switch) (on|off) (the )?(location|gps)$")) {
            host.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Here are your location settings.");
            return true;
        }
        if (s.matches(".*\\bnfc\\b.*")) {
            Intent i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_NFC) : new Intent(Settings.ACTION_NFC_SETTINGS);
            host.launch(i, "Here's NFC.");
            return true;
        }
        if (s.matches(".*\\b(battery saver|power saving|power saver)\\b.*")) {
            host.launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), "Here's power saving.");
            return true;
        }
        if (s.matches("(open |show |go to )?(the )?settings|(open |show )?(my )?phone settings")) {
            host.launch(new Intent(Settings.ACTION_SETTINGS), "Opening Settings.");
            return true;
        }
        if (s.matches("(go )?home|go to (the )?home screen|home screen")) {
            host.launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "");
            return true;
        }
        return false;
    }

    private void setTorch(boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    cm.setTorchMode(id, on);
                    host.say(on ? "Flashlight on." : "Flashlight off.");
                    return;
                }
            }
            host.say("I couldn't find a flashlight on this phone.");
        } catch (Exception e) {
            host.say("The flashlight is busy — is the camera open?");
        }
    }

    private void volume(String s) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int stream = s.matches(".*\\b(ring|ringer|ringtone)\\b.*") ? AudioManager.STREAM_RING
                : s.matches(".*\\balarm\\b.*") ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(stream);
        int flags = AudioManager.FLAG_SHOW_UI;
        Matcher pct = Pattern.compile("(\\d{1,3})\\s*(%|percent)?").matcher(TimeParser.wordsToDigits(s));
        if (s.matches(".*\\bunmute\\b.*")) {
            am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags);
            host.say("Unmuted.");
        } else if (s.matches(".*\\bmute\\b.*")) {
            am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags);
            host.say("Muted.");
        } else if (s.matches(".*\\b(max|maximum|full|all the way up|highest)\\b.*")) {
            am.setStreamVolume(stream, max, flags);
            host.say("Volume is at max.");
        } else if (s.matches(".*\\b(min|minimum|lowest|all the way down)\\b.*")) {
            am.setStreamVolume(stream, 1, flags);
            host.say("Volume is at the lowest level.");
        } else if (pct.find() && s.matches(".*\\b(to|at|set)\\b.*")) {
            int p = Math.max(0, Math.min(100, Integer.parseInt(pct.group(1))));
            if (pct.group(2) == null && p <= max && !s.contains("percent")) p = p * 100 / max; // "volume 10" = step 10
            am.setStreamVolume(stream, Math.round(max * p / 100f), flags);
            host.say("Volume set to " + p + "%.");
        } else if (s.matches(".*\\b(down|lower|decrease|reduce|quieter|softer)\\b.*")) {
            for (int i = 0; i < 2; i++) am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags);
            host.say("Turned it down.");
        } else {
            for (int i = 0; i < 2; i++) am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags);
            host.say("Turned it up.");
        }
    }

    private void brightness(String s) {
        if (!Settings.System.canWrite(ctx)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
            host.launch(i, "To change brightness, turn on \"Modify system settings\" for Voice Agent, then ask me again.");
            return;
        }
        android.content.ContentResolver cr = ctx.getContentResolver();
        if (s.matches(".*\\b(auto|automatic|adaptive)\\b.*")) {
            boolean off = s.matches(".*\\b(off|disable)\\b.*");
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    off ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            host.say(off ? "Adaptive brightness off." : "Adaptive brightness on.");
            return;
        }
        int cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
        int target;
        Matcher pct = Pattern.compile("(\\d{1,3})").matcher(TimeParser.wordsToDigits(s));
        if (s.matches(".*\\b(max|maximum|full|highest|brightest)\\b.*")) target = 255;
        else if (s.matches(".*\\b(min|minimum|lowest|darkest)\\b.*")) target = 5;
        else if (pct.find()) target = Math.round(Math.min(100, Integer.parseInt(pct.group(1))) * 2.55f);
        else if (s.matches(".*\\b(down|lower|decrease|reduce|dimmer|dim)\\b.*")) target = Math.max(5, cur - 64);
        else target = Math.min(255, cur + 64);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target);
        host.say("Brightness set to " + Math.round(target / 2.55f) + "%.");
    }

    private void doNotDisturb(boolean on) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            host.launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                    "First, allow Do Not Disturb access for Voice Agent, then ask me again.");
            return;
        }
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
        host.say(on ? "Do Not Disturb is on." : "Do Not Disturb is off.");
    }

    private void ringer(String s) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int mode;
        String msg;
        if (s.contains("vibrat")) { mode = AudioManager.RINGER_MODE_VIBRATE; msg = "Vibrate mode on."; }
        else if (s.matches(".*\\b(silent|silence)\\b.*") && !s.matches(".*\\b(off|disable)\\b.*")) { mode = AudioManager.RINGER_MODE_SILENT; msg = "Silent mode on."; }
        else { mode = AudioManager.RINGER_MODE_NORMAL; msg = "Sound is on."; }
        try {
            am.setRingerMode(mode);
            host.say(msg);
        } catch (SecurityException e) {
            host.launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                    "Android needs Do Not Disturb access for that. Allow it for Voice Agent, then ask me again.");
        }
    }

    // =====================================================================================
    // Media
    // =====================================================================================

    private boolean media(String s) {
        if (s.matches("(pause|stop)( the| my)?( music| song| playback| video| audio| podcast)?|pause it|stop playing|stop the music")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.");
            return true;
        }
        if (s.matches("play( some| my| the)? (music|songs?)|play something|play anything")) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            // Nothing paused to resume? Ask a music app to just play something.
            if (!am.isMusicActive() && playAnything()) return true;
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "");
            return true;
        }
        if (s.matches("(resume|play|continue|unpause|start)( the| my)?( music| song| playback| video| audio| podcast)?( again)?|keep playing|play it")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "");
            return true;
        }
        if (s.matches("(next|skip)( this)?( song| track| one| video)?|play (the )?next( song| track)?|skip (this|it)")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "");
            return true;
        }
        if (s.matches("(previous|last|prev)( song| track| one)?|play (the )?previous( song| track)?|go back a (song|track)")) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "");
            return true;
        }
        Matcher m = Pattern.compile("^(?:play|put on|listen to|i want to listen to)\\s+(.+?)(?:\\s+(?:on|in|using|from)\\s+(youtube music|yt music|youtube|spotify|jiosaavn|saavn|gaana|wynk|amazon music|samsung music))?$").matcher(s);
        if (m.find()) {
            String query = m.group(1).replaceAll("^(some|the song|song|the|a)\\s+", "").trim();
            String app = m.group(2);
            playMusic(query, app);
            return true;
        }
        return false;
    }

    private boolean playAnything() {
        String[] apps = {"com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats",
                "com.gaana", "com.bsbportal.music", "com.sec.android.app.music"};
        for (String pkg : apps) {
            if (isInstalled(pkg) && playFromSearch("", pkg)) return true;
        }
        return false;
    }

    private void mediaKey(int code, String text) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        host.close(text);
    }

    private void playMusic(String query, String app) {
        String q = query;
        String ytMusic = "com.google.android.apps.youtube.music";
        if (app == null) {
            // Default: Spotify or YouTube Music if installed (they start playing right away), else YouTube.
            if (isInstalled("com.spotify.music") && playFromSearch(q, "com.spotify.music")) return;
            if (isInstalled(ytMusic) && playFromSearch(q, ytMusic)) return;
            youtube(q);
            return;
        }
        if (app.equals("youtube music") || app.equals("yt music")) {
            if (!playFromSearch(q, ytMusic)) youtube(q);
            return;
        }
        String pkg;
        switch (app) {
            case "spotify": pkg = "com.spotify.music"; break;
            case "jiosaavn": case "saavn": pkg = "com.jio.media.jiobeats"; break;
            case "gaana": pkg = "com.gaana"; break;
            case "wynk": pkg = "com.bsbportal.music"; break;
            case "amazon music": pkg = "com.amazon.mp3"; break;
            case "samsung music": pkg = "com.sec.android.app.music"; break;
            default: pkg = null;
        }
        if (pkg != null && playFromSearch(q, pkg)) return;
        if ("youtube".equals(app)) {
            youtube(q);
            return;
        }
        if (!playFromSearch(q, null)) youtube(q);
    }

    private boolean playFromSearch(String query, String pkg) {
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null) i.setPackage(pkg);
        return host.launch(i, query.isEmpty() ? "Playing some music." : "Playing " + query + ".");
    }

    private void youtube(String q) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)));
        i.setPackage("com.google.android.youtube");
        if (!host.launch(i, "Here's " + q + " on YouTube.")) {
            host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))),
                    "Here's " + q + " on YouTube.");
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // May also be hidden by Android's package visibility rules; we just try launching anyway.
            return false;
        }
    }

    // =====================================================================================
    // Alarms & timers (handed to Samsung Clock)
    // =====================================================================================

    private boolean alarmsAndTimers(String s) {
        boolean timer = s.matches(".*\\btimer\\b.*|.*\\bcount ?down\\b.*");
        boolean alarm = !timer && s.matches(".*\\b(alarm|alarms|wake me( up)?|wake up call)\\b.*");
        if (!timer && !alarm) return false;
        boolean cancel = s.matches(".*\\b(cancel|delete|remove|turn off|stop|dismiss|disable|clear)\\b.*");
        boolean show = s.matches(".*\\b(show|list|what|which|see|check)\\b.*");

        if (timer) {
            if (cancel || show) {
                host.launch(new Intent(AlarmClock.ACTION_SHOW_TIMERS), cancel ? "Here are your timers — tap to stop one." : "Here are your timers.");
                return true;
            }
            int sec = TimeParser.parseDurationSeconds(s);
            if (sec <= 0) {
                pending = Pending.TIMER_LENGTH;
                host.ask("For how long?");
            } else {
                setTimer(sec);
            }
            return true;
        }

        if (cancel || show) {
            host.launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS), cancel ? "Here are your alarms — switch off the one you don't need." : "Here are your alarms.");
            return true;
        }
        int[] clock = TimeParser.parseClock(s);
        if (clock == null) {
            int sec = TimeParser.parseDurationSeconds(s);
            if (sec > 0 && s.matches(".*\\b(in|after)\\b.*")) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.SECOND, sec);
                setAlarm(c, label(s));
                return true;
            }
            pending = Pending.ALARM_TIME;
            host.ask("What time should I set it for?");
            return true;
        }
        // "Wake me up at 7" means 7 AM, even when said in the evening.
        if (clock[2] == 0 && s.matches(".*\\bwake\\b.*") && clock[0] < 12) clock = new int[]{clock[0], clock[1], 1};
        setAlarm(TimeParser.nextOccurrence(clock, s), label(s));
        return true;
    }

    private static String label(String s) {
        Matcher m = Pattern.compile("\\b(?:called|named|labell?ed|for)\\s+([a-z][a-z ]+?)(?:\\s+(?:at|for|in|tomorrow)\\b|$)").matcher(s);
        if (m.find()) {
            String l = m.group(1).trim();
            if (!l.matches("(tomorrow|today|tonight|the morning|morning|me|\\d.*)")) return l;
        }
        return null;
    }

    private void setAlarm(Calendar when, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, when.get(Calendar.HOUR_OF_DAY))
                .putExtra(AlarmClock.EXTRA_MINUTES, when.get(Calendar.MINUTE))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        Calendar now = Calendar.getInstance();
        if (!TimeParser.sameDay(when, now)) {
            Calendar tomorrow = (Calendar) now.clone();
            tomorrow.add(Calendar.DAY_OF_MONTH, 1);
            if (!TimeParser.sameDay(when, tomorrow)) {
                ArrayList<Integer> days = new ArrayList<>();
                days.add(when.get(Calendar.DAY_OF_WEEK));
                i.putExtra(AlarmClock.EXTRA_DAYS, days);
            }
        }
        if (label != null) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        String text = "Your alarm is set for " + TimeParser.speakWhen(when) + ".";
        if (!host.launchQuiet(i)) {
            host.say("I couldn't find a clock app that accepts alarms.");
            return;
        }
        host.say(text);
    }

    private void setTimer(int seconds) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Voice Agent timer");
        if (!host.launchQuiet(i)) {
            host.say("I couldn't find a clock app that accepts timers.");
            return;
        }
        host.say("Timer set for " + TimeParser.describeDuration(seconds) + ".");
    }

    // =====================================================================================
    // Reminders (kept by this app, delivered as notifications)
    // =====================================================================================

    private static final Pattern DURATION_CLAUSE = Pattern.compile(
            "\\b(?:in|after)\\s+((?:\\d+(?:\\.\\d+)?|an?|half an?)\\s*(?:and a half\\s*)?(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|hour)(?:\\s*(?:and\\s*)?(?:\\d+|a half)\\s*(?:minutes?|mins?|seconds?|secs?)?)?)");
    private static final Pattern CLOCK_CLAUSE = Pattern.compile(
            "\\b(?:at|by|around)\\s+(?:\\d{1,2}(?:[:.]\\d{2})?(?:\\s\\d{2})?\\s*(?:am|pm)?|noon|midnight)|\\b(?:half past|quarter past|quarter to)\\s+\\d{1,2}(?:\\s*(?:am|pm))?|\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)\\b");
    private static final Pattern DAY_CLAUSE = Pattern.compile(
            "\\b(?:tomorrow(?: morning| afternoon| evening| night)?|today|tonight|this (?:morning|afternoon|evening)|in the (?:morning|afternoon|evening)|at night|(?:on |next |this )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b");

    /** Pull a moment in time out of a sentence ("in 10 minutes", "tomorrow at 9", "at 5 pm"). */
    private Calendar whenFrom(String s) {
        String d = TimeParser.wordsToDigits(s);
        Matcher dur = DURATION_CLAUSE.matcher(d);
        if (dur.find()) {
            int sec = TimeParser.parseDurationSeconds(dur.group(1));
            if (sec > 0) {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.SECOND, sec);
                return c;
            }
        }
        int[] clock = TimeParser.parseClock(d);
        if (clock != null) return TimeParser.nextOccurrence(clock, d);
        if (d.matches(".*\\b(tomorrow|tonight|this evening|this afternoon|this morning|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b.*")) {
            int defaultHour = d.contains("tonight") || d.contains("evening") ? 20 : d.contains("afternoon") ? 14 : 9;
            return TimeParser.nextOccurrence(new int[]{defaultHour, 0, 1}, d);
        }
        return null;
    }

    /** Remove the time words, leaving what the reminder/event is about. */
    private static String stripTime(String s) {
        String d = TimeParser.wordsToDigits(s);
        d = DURATION_CLAUSE.matcher(d).replaceAll(" ");
        d = CLOCK_CLAUSE.matcher(d).replaceAll(" ");
        d = DAY_CLAUSE.matcher(d).replaceAll(" ");
        return d.replaceAll("\\s+", " ").trim();
    }

    private boolean reminders(String s, String raw) {
        Matcher m = Pattern.compile("^(?:remind me|set (?:a |an )?reminder|create (?:a )?reminder|add (?:a )?reminder|new reminder|make (?:a )?reminder)\\b\\s*(.*)$").matcher(s);
        if (!m.find()) {
            if (s.matches("(show|list|what are) (my )?reminders|do i have (any )?reminders")) {
                int n = Reminders.countUpcoming(ctx);
                host.say(n == 0 ? "You don't have any upcoming reminders." : "You have " + n + " upcoming reminder" + (n == 1 ? "." : "s."));
                return true;
            }
            return false;
        }
        String rest = m.group(1);
        Calendar when = whenFrom(rest);
        String what = stripTime(rest)
                .replaceAll("^(to|that|about|for|me to|me that|me about|me)\\s+", "")
                .replaceAll("^(to|that|about)\\s+", "")
                .replaceAll("\\s+(to|that)$", "")
                .trim();
        // Turn "me to call my mom" style leftovers into a nice sentence, keeping original capitals.
        if (!what.isEmpty()) {
            String o = original(raw, what);
            what = Character.toUpperCase(o.charAt(0)) + o.substring(1);
        }
        if (what.isEmpty()) {
            pendingText = null;
            pendingReminderTime = when; // may be null; we'll ask for it after the text
            pending = Pending.REMINDER_TEXT;
            host.ask("What should I remind you about?");
            return true;
        }
        if (when == null) {
            pendingText = what;
            pending = Pending.REMINDER_TIME;
            host.ask("When should I remind you?");
            return true;
        }
        scheduleReminder(what, when);
        return true;
    }

    private Calendar pendingReminderTime;

    private void scheduleReminder(String text, Calendar when) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)
                && permissionsAsked.add(Manifest.permission.POST_NOTIFICATIONS)) {
            // Ask once; reminder is scheduled regardless.
            host.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, null);
        }
        Reminders.schedule(ctx, when.getTimeInMillis(), text);
        host.say("Okay, I'll remind you " + TimeParser.speakWhen(when) + ": " + text);
    }

    // =====================================================================================
    // Calendar
    // =====================================================================================

    private boolean calendar(String s, String raw) {
        if (s.matches(".*\\b(what's|what is|whats|show|read|check|tell me)\\b.*\\b(calendar|schedule|agenda|plans)\\b.*")
                || s.matches("(do i have|have i got|are there) (any )?(meetings|events|appointments|plans).*")
                || s.matches("(my )?(calendar|schedule|agenda)( for)?( today| tomorrow)?")) {
            Perm p = ensure(raw, Manifest.permission.READ_CALENDAR);
            if (p == Perm.REQUESTED) return true;
            if (p == Perm.DENIED) {
                host.say("I need calendar access to read your schedule.");
                return true;
            }
            readCalendar(s.contains("tomorrow") ? 1 : 0);
            return true;
        }
        Matcher add = Pattern.compile("^(?:add|create|schedule|put|make|set up|book)\\s+(?:an? )?(?:event|meeting|appointment|calendar event)?\\s*(?:called |named |for |about )?(.*?)\\s*(?:(?:to|in|on) (?:my |the )?calendar)?$").matcher(s);
        if ((s.matches(".*\\bcalendar\\b.*") || s.matches("^(schedule|add|create|book|set up) (an? )?(meeting|event|appointment)\\b.*")) && add.find()) {
            String rest = add.group(1).replaceAll("(?:to|in|on) (?:my |the )?calendar", "");
            Calendar when = whenFrom(rest);
            String title = stripTime(rest).replaceAll("^(an?|the|event|meeting|appointment|called|named|for|about)\\s+", "").trim();
            if (title.isEmpty()) title = s.contains("meeting") ? "Meeting" : "Event";
            else title = original(raw, title);
            Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, Character.toUpperCase(title.charAt(0)) + title.substring(1));
            if (when != null) {
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, when.getTimeInMillis());
                i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, when.getTimeInMillis() + 3_600_000L);
            }
            host.launch(i, "Here's your event" + (when != null ? " for " + TimeParser.speakWhen(when) : "") + ". Tap save to confirm.");
            return true;
        }
        return false;
    }

    private void readCalendar(int dayOffset) {
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_MONTH, dayOffset);
        if (dayOffset > 0) {
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
        }
        Calendar end = (Calendar) start.clone();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, start.getTimeInMillis());
        ContentUris.appendId(b, end.getTimeInMillis());
        List<String> items = new ArrayList<>();
        String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
        try (Cursor c = ctx.getContentResolver().query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                String title = c.getString(0);
                Calendar at = Calendar.getInstance();
                at.setTimeInMillis(c.getLong(1));
                boolean allDay = c.getInt(2) == 1;
                items.add((title == null ? "Untitled" : title) + (allDay ? " (all day)" : " at " + TimeParser.speakTime(at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))));
            }
        } catch (SecurityException e) {
            host.say("I need calendar access to read your schedule.");
            return;
        }
        String day = dayOffset == 0 ? "today" : "tomorrow";
        if (items.isEmpty()) {
            host.say(dayOffset == 0 ? "Nothing else on your calendar today." : "Your calendar is clear tomorrow.");
            return;
        }
        StringBuilder sb = new StringBuilder("You have " + items.size() + (items.size() == 1 ? " event " : " events ") + (dayOffset == 0 ? "left today" : day) + ": ");
        for (int i = 0; i < items.size() && i < 5; i++) {
            if (i > 0) sb.append(i == items.size() - 1 || i == 4 ? ", and " : ", ");
            sb.append(items.get(i));
        }
        if (items.size() > 5) sb.append(", and more");
        host.say(sb.append('.').toString());
    }

    // =====================================================================================
    // Notes (stored privately on the phone)
    // =====================================================================================

    private boolean notes(String s, String raw) {
        if (s.matches("(read|show|what are|list|tell me|open)( me)? (my |the )?notes|what did i (note|save)|my notes")) {
            JSONArray n = loadNotes();
            if (n.length() == 0) {
                host.say("You don't have any notes yet. Say \"take a note\" to add one.");
                return true;
            }
            StringBuilder b = new StringBuilder(n.length() == 1 ? "You have one note: " : "Your latest notes: ");
            for (int i = n.length() - 1, k = 0; i >= 0 && k < 5; i--, k++) {
                if (k > 0) b.append("; ");
                b.append(n.optString(i));
            }
            host.say(b.append('.').toString());
            return true;
        }
        if (s.matches("(delete|clear|erase|remove) (all )?(my |the )?notes")) {
            prefs().edit().remove("notes").apply();
            host.say("All notes deleted.");
            return true;
        }
        Matcher m = Pattern.compile("^(?:take|make|write|create|add|jot down)(?: a| me a)? note(?: that| saying| to| about|:)?\\s*(.*)$|^(?:note|note down|write down|remember)(?: that)?\\s+(.+)$").matcher(s);
        if (m.find()) {
            String text = m.group(1) != null ? m.group(1) : m.group(2);
            if (text == null || text.trim().isEmpty()) {
                pending = Pending.NOTE_TEXT;
                host.ask("What would you like the note to say?");
                return true;
            }
            saveNote(cleanSentence(original(raw, text.trim())));
            return true;
        }
        return false;
    }

    private void saveNote(String text) {
        if (text.isEmpty()) {
            host.say("The note was empty, so I didn't save it.");
            return;
        }
        JSONArray n = loadNotes();
        n.put(text);
        prefs().edit().putString("notes", n.toString()).apply();
        host.say("Noted: " + text);
    }

    private JSONArray loadNotes() {
        try {
            return new JSONArray(prefs().getString("notes", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("brain", Context.MODE_PRIVATE);
    }

    // =====================================================================================
    // Calls, texts, WhatsApp, email
    // =====================================================================================

    private boolean communication(String s, String raw) {
        // Call
        Matcher call = Pattern.compile("^(?:call|phone|dial|ring|video call|facetime)(?: up)? (.+?)(?: on (?:speaker|his|her|their|the) ?(?:phone|mobile|cell|speaker)?)?$").matcher(s);
        if (call.find() && !s.matches("^call (me|it) .*")) {
            String who = call.group(1);
            boolean video = s.startsWith("video call") || s.startsWith("facetime");
            String digits = who.replaceAll("[^0-9+]", "");
            if (digits.length() >= 3 && who.replaceAll("[0-9+ ()-]", "").isEmpty()) {
                placeCall(digits, digits);
                return true;
            }
            if (who.matches("(back|again|the last number)")) {
                host.launch(new Intent(Intent.ACTION_VIEW, android.provider.CallLog.Calls.CONTENT_URI), "Here are your recent calls.");
                return true;
            }
            Perm p = ensure(raw, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE);
            if (p == Perm.REQUESTED) return true;
            if (!granted(Manifest.permission.READ_CONTACTS)) {
                host.say("I need permission to read your contacts to call someone by name.");
                return true;
            }
            Contacts.Match c = Contacts.find(ctx, who);
            if (c == null) {
                host.say("I couldn't find " + who + " in your contacts.");
                return true;
            }
            if (video) {
                whatsappOrSms(c.name, c.number, "", true);
                return true;
            }
            placeCall(c.number, c.name);
            return true;
        }

        // WhatsApp
        Matcher wa = Pattern.compile("^(?:send (?:a )?)?(?:whatsapp|whats app)(?: message)?(?: to)? (.+)$|^(?:message|text|send a message to) (.+?) on whats ?app(?: (?:saying|that) (.+))?$").matcher(s);
        if (wa.find()) {
            String rest = wa.group(1) != null ? wa.group(1) : wa.group(2) + (wa.group(3) != null ? " saying " + wa.group(3) : "");
            return messageTo(rest, raw, true);
        }

        // SMS
        Matcher sms = Pattern.compile("^(?:text|message|sms|send (?:a |an )?(?:text|message|sms)(?: message)?(?: to)?|send|tell|let) (.+)$").matcher(s);
        if (sms.find() && !s.matches("^(tell|let) me\\b.*") && !s.matches("^send (an? )?e-?mail.*")) {
            String rest = sms.group(1);
            if (s.startsWith("let ")) {
                Matcher know = Pattern.compile("^(.+?) know (?:that )?(.+)$").matcher(rest);
                if (!know.find()) return false;
                rest = know.group(1) + " saying " + know.group(2);
            } else if (s.startsWith("tell ")) {
                if (!rest.matches(".+? (that|to|saying|i'm|i am|i'll|i will|we're|we are) .+")) return false;
            }
            return messageTo(rest, raw, false);
        }

        // Email
        Matcher mail = Pattern.compile("^(?:send |write |compose )?(?:an? )?e-?mail(?: to)?(?: (.+?))?(?: (?:about|saying|subject) (.+))?$").matcher(s);
        if (mail.find()) {
            String to = mail.group(1) == null ? "" : mail.group(1).replace(" at ", "@").replace(" dot ", ".").replace(" ", "");
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (to.contains("@") ? to : "")));
            if (mail.group(2) != null) i.putExtra(Intent.EXTRA_SUBJECT, original(raw, mail.group(2)));
            host.launch(i, "Here's your email.");
            return true;
        }
        return false;
    }

    /** "mom saying I'm late" / "mom i'm late" / "mom" */
    private boolean messageTo(String rest, String raw, boolean whatsapp) {
        String name, body = "";
        Matcher split = Pattern.compile("^(.+?)\\s+(?:saying|that says|that|to say|and say|and tell (?:him|her|them)|telling (?:him|her|them)|message)\\s+(.+)$").matcher(rest);
        Perm p = Perm.GRANTED;
        String digits = rest.split(" ")[0].replaceAll("[^0-9+]", "");
        boolean numberGiven = digits.length() >= 5;
        if (!numberGiven) {
            p = ensure(raw, Manifest.permission.READ_CONTACTS);
            if (p == Perm.REQUESTED) return true;
            if (!granted(Manifest.permission.READ_CONTACTS)) {
                host.say("I need permission to read your contacts to message someone by name.");
                return true;
            }
        }
        Contacts.Match match = null;
        if (split.find()) {
            name = split.group(1);
            body = split.group(2);
            if (!numberGiven) match = Contacts.find(ctx, name);
        } else if (numberGiven) {
            name = digits;
            body = rest.substring(rest.indexOf(' ') + 1).trim();
            if (body.equals(rest)) body = "";
        } else {
            // Try the longest run of words that matches a contact: "john smith i'm outside"
            String[] w = rest.split(" ");
            name = w[0];
            for (int k = Math.min(4, w.length); k >= 1; k--) {
                String candidate = join(w, 0, k);
                Contacts.Match m = Contacts.find(ctx, candidate);
                if (m != null && m.score >= 60) {
                    match = m;
                    name = candidate;
                    body = join(w, k, w.length);
                    break;
                }
            }
            if (match == null) {
                match = Contacts.find(ctx, name);
                body = join(w, 1, w.length);
            }
        }
        String number;
        String display;
        if (numberGiven) {
            number = digits;
            display = digits;
        } else {
            if (match == null) {
                host.say("I couldn't find " + name + " in your contacts.");
                return true;
            }
            number = match.number;
            display = match.name;
        }
        body = body.trim();
        if (body.isEmpty()) {
            pendingName = display;
            pendingNumber = number;
            pendingWhatsApp = whatsapp;
            pending = Pending.SMS_BODY;
            host.ask("What do you want to say to " + display + "?");
            return true;
        }
        sendMessage(display, number, cleanSentence(original(raw, body)), whatsapp);
        return true;
    }

    private void sendMessage(String name, String number, String body, boolean whatsapp) {
        if (whatsapp) {
            whatsappOrSms(name, number, body, false);
            return;
        }
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                .putExtra("sms_body", body);
        host.launch(i, "Here's your message to " + name + ". Just tap send.");
    }

    private void whatsappOrSms(String name, String number, String body, boolean videoCall) {
        String e164 = toE164(number);
        if (e164 == null) {
            host.say("I need " + name + "'s number with a country code to use WhatsApp.");
            return;
        }
        String url = "https://api.whatsapp.com/send?phone=" + e164.replace("+", "")
                + (body.isEmpty() ? "" : "&text=" + Uri.encode(body));
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        String text = videoCall ? "Opening WhatsApp with " + name + " — tap the video button." : "Here's your WhatsApp to " + name + ". Just tap send.";
        if (!host.launch(i.setPackage("com.whatsapp"), text)) {
            if (!host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), text)) {
                host.say("WhatsApp doesn't seem to be installed.");
            }
        }
    }

    private String toE164(String number) {
        String n = number.replaceAll("[^0-9+]", "");
        if (n.startsWith("+")) return n;
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        String iso = tm != null ? tm.getSimCountryIso() : null;
        if (iso == null || iso.isEmpty()) iso = tm != null ? tm.getNetworkCountryIso() : null;
        if (iso == null || iso.isEmpty()) iso = Locale.getDefault().getCountry();
        String r = PhoneNumberUtils.formatNumberToE164(n, iso.toUpperCase(Locale.ROOT));
        return r;
    }

    private void placeCall(String number, String name) {
        boolean emergency = number.matches("(911|112|999|100|101|102|108|000|110|119)");
        boolean direct = !emergency && granted(Manifest.permission.CALL_PHONE);
        Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        host.launch(i, direct ? "Calling " + name + "." : "Here's " + name + " — tap call.");
    }

    // =====================================================================================
    // Maps
    // =====================================================================================

    private boolean navigation(String s) {
        Matcher nav = Pattern.compile("^(?:navigate|navigation|directions|get directions|give me directions|take me|drive me|drive|walk me|walk|route|how (?:do|can) i get|how to get|how to go|guide me)(?: me)?(?: to| home)?\\s*(.*)$").matcher(s);
        if (nav.find() && (s.contains(" to ") || s.matches(".*\\bhome\\b.*") || s.startsWith("navigate") || s.startsWith("directions"))) {
            String dest = nav.group(1).trim();
            if (dest.isEmpty() && s.matches(".*\\bhome\\b.*")) dest = "home";
            dest = dest.replaceAll("^(the )", "");
            if (dest.isEmpty()) {
                host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), "Opening Maps.");
                return true;
            }
            String mode = s.matches("^walk.*|.*\\bwalking\\b.*") ? "&mode=w" : s.matches(".*\\b(bike|cycling)\\b.*") ? "&mode=b" : "";
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + mode));
            if (!host.launch(i, "Getting directions to " + dest + ".")) {
                host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "Here's " + dest + " on the map.");
            }
            return true;
        }
        Matcher near = Pattern.compile("^(?:find|show me|search for|look for|where is|where's|where are|wheres)?\\s*(?:a |an |the |some )?(?:nearest |closest |nearby )?(.+?)\\s+(?:near me|nearby|around me|around here|close by|near here|on the map|on maps)$").matcher(s);
        Matcher nearest = Pattern.compile("^(?:find|show me|where is|where's)?\\s*(?:the )?(?:nearest|closest) (.+)$").matcher(s);
        String place = null;
        if (near.find()) place = near.group(1);
        else if (nearest.find()) place = nearest.group(1);
        if (place != null) {
            host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))), "Here are " + place + " nearby.");
            return true;
        }
        if (s.matches("(open )?(google )?maps|where am i|show (me )?my location")) {
            host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), "Here's where you are.");
            return true;
        }
        return false;
    }

    // =====================================================================================
    // Camera
    // =====================================================================================

    private boolean camera(String s) {
        if (s.matches(".*\\b(take|click|snap|capture) (a |an )?(selfie|front photo)\\b.*|^selfie$")) {
            Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .putExtra("android.intent.extras.CAMERA_FACING", 1)
                    .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    .putExtra("camerafacing", "front");
            host.launch(i, "Smile!");
            return true;
        }
        if (s.matches(".*\\b(take|click|snap|capture) (a |an )?(photo|picture|pic|snap|shot)\\b.*|^(open |launch |start )?(the )?camera( app)?$")) {
            host.launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Opening the camera.");
            return true;
        }
        if (s.matches(".*\\b(record|take|shoot|capture) (a )?video\\b.*")) {
            host.launch(new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), "Ready to record.");
            return true;
        }
        return false;
    }

    // =====================================================================================
    // Fun
    // =====================================================================================

    private static final String[] JOKES = {
            "Why don't scientists trust atoms? Because they make up everything.",
            "I told my phone a joke about battery life. It didn't get a charge out of it.",
            "Why did the scarecrow win an award? He was outstanding in his field.",
            "What do you call a fake noodle? An impasta.",
            "Why can't a bicycle stand up by itself? It's two tired.",
            "I'm reading a book about anti-gravity. It's impossible to put down.",
            "Why did the smartphone need glasses? It lost all its contacts.",
            "What do you call a bear with no teeth? A gummy bear.",
            "Parallel lines have so much in common. It's a shame they'll never meet.",
            "Why don't eggs tell jokes? They'd crack each other up.",
            "How does a penguin build its house? Igloos it together.",
            "I would tell you a UDP joke, but you might not get it.",
    };

    private boolean fun(String s) {
        if (s.matches(".*\\b(joke|make me laugh|something funny)\\b.*")) {
            host.say(JOKES[random.nextInt(JOKES.length)]);
            return true;
        }
        if (s.matches(".*\\b(flip|toss) (a )?coin\\b.*|heads or tails")) {
            host.say(random.nextBoolean() ? "It's heads." : "It's tails.");
            return true;
        }
        if (s.matches(".*\\broll (a |the )?(die|dice)\\b.*")) {
            host.say("You rolled a " + (random.nextInt(6) + 1) + ".");
            return true;
        }
        Matcher rn = Pattern.compile("(?:random number|pick a number|choose a number)(?: between| from)? (\\d+) (?:and|to) (\\d+)").matcher(TimeParser.wordsToDigits(s));
        if (rn.find()) {
            int a = Integer.parseInt(rn.group(1)), b = Integer.parseInt(rn.group(2));
            int lo = Math.min(a, b), hi = Math.max(a, b);
            host.say("How about " + (lo + random.nextInt(hi - lo + 1)) + "?");
            return true;
        }
        if (s.matches(".*\\b(random number|pick a number)\\b.*")) {
            host.say("How about " + (random.nextInt(100) + 1) + "?");
            return true;
        }
        if (s.matches(".*\\b(meaning of life)\\b.*")) {
            host.say("42. Or maybe just good food and good people.");
            return true;
        }
        return false;
    }

    // =====================================================================================
    // Translate (opens Google Translate)
    // =====================================================================================

    private static final Map<String, String> LANGS = new HashMap<>();

    static {
        String[][] l = {{"hindi", "hi"}, {"tamil", "ta"}, {"telugu", "te"}, {"kannada", "kn"}, {"malayalam", "ml"},
                {"marathi", "mr"}, {"bengali", "bn"}, {"gujarati", "gu"}, {"punjabi", "pa"}, {"urdu", "ur"},
                {"odia", "or"}, {"english", "en"}, {"spanish", "es"}, {"french", "fr"}, {"german", "de"},
                {"italian", "it"}, {"portuguese", "pt"}, {"russian", "ru"}, {"japanese", "ja"}, {"korean", "ko"},
                {"chinese", "zh-CN"}, {"mandarin", "zh-CN"}, {"arabic", "ar"}, {"turkish", "tr"}, {"dutch", "nl"},
                {"indonesian", "id"}, {"thai", "th"}, {"vietnamese", "vi"}, {"greek", "el"}, {"nepali", "ne"},
                {"sinhala", "si"}, {"swahili", "sw"}, {"polish", "pl"}, {"ukrainian", "uk"}, {"hebrew", "iw"}};
        for (String[] p : l) LANGS.put(p[0], p[1]);
    }

    private boolean translate(String s, String raw) {
        Matcher m = Pattern.compile("^translate (.+?) (?:to|into|in) ([a-z]+)$|^how (?:do|would) (?:you|i) say (.+?) in ([a-z]+)$|^what is (.+?) in ([a-z]+)$").matcher(s);
        if (!m.find()) return false;
        String text = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(5);
        String lang = m.group(2) != null ? m.group(2) : m.group(4) != null ? m.group(4) : m.group(6);
        String code = LANGS.get(lang);
        if (code == null) return false;
        String url = "https://translate.google.com/?sl=auto&tl=" + code + "&op=translate&text=" + Uri.encode(original(raw, text));
        host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Here's \"" + text + "\" in " + WebSkills.capitalize(lang) + ".");
        return true;
    }

    // =====================================================================================
    // Weather (Open-Meteo, free, no key)
    // =====================================================================================

    private boolean weather(String s) {
        if (!s.matches(".*\\b(weather|forecast|temperature|rain|raining|snow|snowing|umbrella|sunny|humid|how hot|how cold|degrees outside)\\b.*")) return false;
        if (s.matches(".*\\b(song|play|music)\\b.*")) return false;
        int when = s.contains("tomorrow") ? 1 : 0;
        Matcher place = Pattern.compile("\\b(?:in|at|for|of)\\s+([a-z][a-z .'-]*?)(?:\\s+(?:today|tomorrow|now|right now|this week|tonight|this evening))?$").matcher(s);
        String city = null;
        if (place.find()) {
            city = place.group(1).trim();
            if (city.matches("(the )?(morning|evening|afternoon|weekend|today|tomorrow|here|my area|my location|my city|outside)")) city = null;
        }
        boolean fahrenheit = "US".equals(Locale.getDefault().getCountry());
        if (city != null) {
            final String c = city;
            host.thinking();
            bg.execute(() -> {
                String ans;
                try {
                    Object[] g = WebSkills.geocode(c);
                    ans = g == null ? "I couldn't find a place called " + c + "."
                            : WebSkills.weather((Double) g[0], (Double) g[1], (String) g[2], when, fahrenheit);
                } catch (Exception e) {
                    ans = null;
                }
                String a = ans != null ? ans : "I couldn't get the weather right now. Check your internet connection.";
                host.runOnUi(() -> host.say(a));
            });
            return true;
        }
        Perm p = ensure(s, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (p == Perm.REQUESTED) return true;
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            host.say("I need your approximate location for local weather. Or ask like \"weather in Mumbai\".");
            return true;
        }
        host.thinking();
        withLocation(loc -> bg.execute(() -> {
            String ans;
            if (loc == null) {
                ans = "I couldn't get your location. Make sure location is on, or ask like \"weather in Mumbai\".";
            } else {
                try {
                    ans = WebSkills.weather(loc.getLatitude(), loc.getLongitude(), null, when, fahrenheit);
                } catch (Exception e) {
                    ans = null;
                }
                if (ans == null) ans = "I couldn't get the weather right now. Check your internet connection.";
            }
            String a = ans;
            host.runOnUi(() -> host.say(a));
        }));
        return true;
    }

    private interface LocationCallback {
        void onLocation(Location l);
    }

    @SuppressWarnings("MissingPermission")
    private void withLocation(LocationCallback cb) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        // Weather doesn't need precision; anything from the last 3 hours is fine.
        if (best != null && System.currentTimeMillis() - best.getTime() < 3 * 3600_000L) {
            cb.onLocation(best);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                    : Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                    : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
            if (provider != null) {
                final Location fallback = best;
                try {
                    lm.getCurrentLocation(provider, null, ctx.getMainExecutor(), l -> cb.onLocation(l != null ? l : fallback));
                    return;
                } catch (SecurityException ignored) {
                }
            }
        }
        cb.onLocation(best);
    }

    // =====================================================================================
    // Open apps
    // =====================================================================================

    private boolean openApp(String s) {
        Matcher m = Pattern.compile("^(open|launch|start|run|show|go to|switch to|bring up)\\s+(?:the |my )?(.+?)(?:\\s+app(?:lication)?)?$").matcher(s);
        if (!m.find()) return false;
        boolean explicit = m.group(1).matches("open|launch|start|run");
        String name = m.group(2).trim();
        if (name.matches("(google )?maps?")) {
            host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), "Opening Maps.");
            return true;
        }
        Apps.Found app = Apps.find(ctx, name);
        if (app == null) {
            // Might be a website: "open wikipedia.org" / "go to amazon"
            if (name.matches("[a-z0-9-]+(\\.| dot )[a-z.]+")) {
                String url = "https://" + name.replace(" dot ", ".");
                host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opening " + name + ".");
                return true;
            }
            if (!explicit) return false; // "show me pictures of cats" -> web search
            host.say("I couldn't find an app called " + name + ".");
            return true;
        }
        host.launch(app.launch, "Opening " + app.label + ".");
        return true;
    }

    // =====================================================================================
    // Math, knowledge, web
    // =====================================================================================

    private boolean math(String s) {
        String ans = MathEval.tryAnswer(s);
        if (ans == null) return false;
        host.say(ans.length() > 20 ? "That's about " + ans + "." : "It's " + ans + ".");
        return true;
    }

    private boolean knowledge(String s) {
        Matcher def = Pattern.compile("^(?:define|definition of|what's the definition of|what is the definition of|meaning of|what's the meaning of|what is the meaning of|what does the word) (?:the word )?([a-z'-]+)(?: mean)?$|^what does ([a-z'-]+) mean$").matcher(s);
        if (def.find()) {
            String word = def.group(1) != null ? def.group(1) : def.group(2);
            host.thinking();
            bg.execute(() -> {
                String a;
                try {
                    a = WebSkills.define(word);
                } catch (Exception e) {
                    a = null;
                }
                String ans = a;
                host.runOnUi(() -> {
                    if (ans != null) host.say(ans);
                    else webSearch("define " + word, "I couldn't find a definition, so here's a web search.");
                });
            });
            return true;
        }
        Matcher search = Pattern.compile("^(?:search|google|search for|search the web for|search google for|search online for|look for|find|look up online)\\s+(.+)$").matcher(s);
        if (search.find() && !s.startsWith("look up ")) {
            webSearch(search.group(1), "Searching for " + search.group(1) + ".");
            return true;
        }
        boolean question = s.matches("^(who|what|what's|whats|when|where|where's|why|how|which|whose|is|are|was|were|do|does|did|can|could|will|would|should|tell me|explain|describe)\\b.*")
                || s.matches("^(look up|wikipedia|search wikipedia for)\\b.*");
        if (!question) return false;
        String topic = topicOf(s);
        if (topic != null && topic.matches("(my|your|you|me|i|this|that|it|up|going on|happening|new|wrong)\\b.*")) topic = null;
        final String t = topic;
        host.thinking();
        bg.execute(() -> {
            String a = null;
            try {
                a = WebSkills.instantAnswer(s);
            } catch (Exception ignored) {
            }
            if (a == null && t != null) {
                try {
                    a = WebSkills.wikipedia(t);
                } catch (Exception ignored) {
                }
            }
            String ans = a;
            host.runOnUi(() -> {
                if (ans != null) host.say(ans);
                else webSearch(s, "Here's what I found on the web.");
            });
        });
        return true;
    }

    /** "how tall is mount everest" -> "mount everest", "when was gandhi born" -> "gandhi". */
    static String topicOf(String s) {
        String t = s;
        t = t.replaceFirst("^(?:tell me (?:something |more )?about|what do you know about|search wikipedia for|wikipedia|look up|explain|describe)\\s+", "");
        t = t.replaceFirst("^how (?:tall|old|big|far|long|high|deep|heavy|large|fast|hot|cold|wide|much does|many people live in)\\s+(?:is|are|was|were|away is)?\\s*", "");
        t = t.replaceFirst("^(?:who|what|when|where|which|whose|why|how)(?:'s| is| are| was| were| did| does| do| has| have)?\\s+", "");
        t = t.replaceFirst("^(?:invented|founded|wrote|discovered|made|created|directed|painted|built|owns|composed|sang|won)\\s+", "");
        t = t.replaceFirst("\\s+(?:born|founded|built|invented|created|made|released|established|discovered|formed|located|from|die|died|start|started|happen|happened|mean)$", "");
        t = t.replaceFirst("^(?:a|an|the)\\s+", "").replaceAll("\\s+(now|today|currently|right now)$", "").trim();
        return t.isEmpty() || t.equals(s) && s.matches("^(is|are|do|does|did|can|could|will|would|should|why)\\b.*") ? null : t;
    }

    private boolean conversions(String s) {
        String u = Conversions.units(s);
        if (u != null) {
            host.say(u);
            return true;
        }
        Object[] q = Conversions.currencyQuery(s);
        if (q == null) return false;
        host.thinking();
        bg.execute(() -> {
            String a;
            try {
                a = Conversions.currency(q);
            } catch (Exception e) {
                a = null;
            }
            String ans = a != null ? a : "I couldn't get exchange rates right now. Check your internet connection.";
            host.runOnUi(() -> host.say(ans));
        });
        return true;
    }

    private void webSearch(String query, String text) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
        if (!host.launch(i, text)) host.say("Sorry, I can't help with that one yet.");
    }

    // =====================================================================================
    // Permissions
    // =====================================================================================

    private enum Perm { GRANTED, REQUESTED, DENIED }

    private boolean granted(String perm) {
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask once per session for any missing permission, then re-run the command. */
    private Perm ensure(String retry, String... perms) {
        List<String> missing = new ArrayList<>();
        boolean anyUnasked = false;
        for (String p : perms) {
            if (!granted(p)) {
                missing.add(p);
                if (!permissionsAsked.contains(p)) anyUnasked = true;
            }
        }
        if (missing.isEmpty()) return Perm.GRANTED;
        if (anyUnasked) {
            permissionsAsked.addAll(missing);
            host.requestPermissions(missing.toArray(new String[0]), retry);
            return Perm.REQUESTED;
        }
        return Perm.DENIED;
    }

    // =====================================================================================

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    private static String join(String[] w, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(w[i]);
        }
        return b.toString();
    }
}
