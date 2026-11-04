package com.voiceagent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Smart mode": Google's Gemini model (free API key from aistudio.google.com) works out what
 * you meant and answers with one structured action, which {@link Brain} then carries out.
 */
public final class Gemini {

    /** Tried in order; aliases first so the app keeps working as Google releases new models. */
    private static final String[] MODELS = {"gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-lite-latest"};
    private static final String PREFS = "smart";

    public static final class KeyException extends IOException {
        KeyException(String m) { super(m); }
    }

    public static final class RateLimitException extends IOException {
        RateLimitException(String m) { super(m); }
    }

    private final List<JSONObject> history = new ArrayList<>();
    private final Context ctx;
    private int modelIndex;
    private boolean noThinkingConfig;

    public Gemini(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        modelIndex = prefs(ctx).getInt("model", 0);
        if (modelIndex >= MODELS.length) modelIndex = 0;
    }

    // ------------------------------------------------------------------ key storage

    public static String key(Context ctx) {
        return prefs(ctx).getString("key", "").trim();
    }

    public static void setKey(Context ctx, String key) {
        prefs(ctx).edit().putString("key", key == null ? "" : key.trim()).apply();
    }

    public static boolean enabled(Context ctx) {
        return !key(ctx).isEmpty();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void clearHistory() {
        history.clear();
    }

    // ------------------------------------------------------------------ the call

    /** Blocking. Returns the parsed action object: {say, action, args, expect_reply}. */
    public JSONObject understand(String userText) throws IOException {
        String key = key(ctx);
        if (key.isEmpty()) throw new KeyException("No key");
        JSONObject user = content("user", userText);
        IOException last = null;
        for (int attempt = 0; attempt < MODELS.length; attempt++) {
            String model = MODELS[(modelIndex + attempt) % MODELS.length];
            try {
                String text = call(model, key, user);
                JSONObject action = parseAction(text);
                modelIndex = (modelIndex + attempt) % MODELS.length;
                prefs(ctx).edit().putInt("model", modelIndex).apply();
                history.add(user);
                history.add(content("model", action.toString()));
                while (history.size() > 12) history.remove(0);
                return action;
            } catch (ModelMissing e) {
                last = e; // try the next model name
            }
        }
        throw last != null ? last : new IOException("No model available");
    }

    private static final class ModelMissing extends IOException {
        ModelMissing(String m) { super(m); }
    }

    private String call(String model, String key, JSONObject user) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt()))));
            JSONArray contents = new JSONArray();
            for (JSONObject h : history) contents.put(h);
            contents.put(user);
            body.put("contents", contents);
            JSONObject gen = new JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 700)
                    .put("responseMimeType", "application/json");
            if (!noThinkingConfig) gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
            body.put("generationConfig", gen);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
            int[] status = new int[1];
            String resp = post(url, key, body.toString(), status);
            if (status[0] == 400 && resp.contains("API_KEY_INVALID") || status[0] == 401 || status[0] == 403 && resp.contains("API key")) {
                throw new KeyException("The Gemini key was rejected");
            }
            if (status[0] == 400 && !noThinkingConfig) {
                noThinkingConfig = true; // older/newer model without this setting: retry without it
                return call(model, key, user);
            }
            if (status[0] == 404) throw new ModelMissing(model);
            if (status[0] == 429) throw new RateLimitException("Free quota used up for now");
            if (status[0] >= 400) {
                String msg = resp;
                try {
                    msg = new JSONObject(resp).getJSONObject("error").optString("message", resp);
                } catch (JSONException ignored) {
                }
                throw new IOException("Gemini error " + status[0] + ": " + (msg.length() > 120 ? msg.substring(0, 120) : msg));
            }

            JSONObject o = new JSONObject(resp);
            JSONArray cands = o.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) throw new IOException("No answer");
            JSONArray parts = cands.getJSONObject(0).optJSONObject("content") == null ? null
                    : cands.getJSONObject(0).getJSONObject("content").optJSONArray("parts");
            if (parts == null) throw new IOException("Empty answer");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                if (p.optBoolean("thought")) continue;
                sb.append(p.optString("text"));
            }
            return sb.toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static JSONObject parseAction(String text) throws IOException {
        String t = text.trim();
        if (t.startsWith("```")) t = t.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IOException("Not JSON: " + text);
        try {
            JSONObject o = new JSONObject(t.substring(a, b + 1));
            if (!o.has("action")) o.put("action", "answer");
            if (!o.has("args") || o.optJSONObject("args") == null) o.put("args", new JSONObject());
            return o;
        } catch (JSONException e) {
            throw new IOException("Bad JSON: " + text);
        }
    }

    private static JSONObject content(String role, String text) {
        try {
            return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", text)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String post(String url, String key, String json, int[] status) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", key);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            status[0] = c.getResponseCode();
            InputStream in = status[0] >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            try (InputStream s = in) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ instructions

    private String systemPrompt() {
        Date now = new Date();
        String nowText = new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.US).format(now);
        String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(now);
        String tz = TimeZone.getDefault().getID();
        String country = Locale.getDefault().getCountry();
        return "You are Voice Agent, a friendly voice assistant on the user's Android phone (like Siri). "
                + "The user speaks; their words come from speech recognition, so expect mis-hearings, missing punctuation, "
                + "filler words and Indian English. Work out what they most likely meant.\n"
                + "Current local time: " + nowText + " (" + iso + ", time zone " + tz + "). Country: " + country + ".\n\n"
                + "Reply with ONE JSON object only, no markdown:\n"
                + "{\"say\": string, \"action\": string, \"args\": object, \"expect_reply\": boolean}\n"
                + "- \"say\": what to speak aloud. Short and natural, at most 2 sentences, no lists, no emoji, no markdown.\n"
                + "- \"expect_reply\": true only when you asked the user a question and need their answer.\n\n"
                + "Actions and their args:\n"
                + "answer {} - just speak \"say\" (chit-chat, facts, explanations, advice, math, conversions).\n"
                + "none {} - you need more information: ask in \"say\" and set expect_reply true.\n"
                + "call {contact?: name as the user said it e.g. \"Mom\", number?: digits}\n"
                + "video_call {contact}\n"
                + "sms {contact?, number?, message}\n"
                + "whatsapp {contact?, number?, message}\n"
                + "email {to?, subject?, body?}\n"
                + "open_app {name}\n"
                + "play_music {query: song/artist/playlist or \"\" for anything, app?: spotify|youtube|youtube music|jiosaavn|gaana|wynk|amazon music}\n"
                + "media {command: pause|resume|next|previous}\n"
                + "set_alarm {hour: 0-23, minute: 0-59, date?: \"YYYY-MM-DD\", label?}\n"
                + "set_timer {seconds}\n"
                + "show_alarms {} / show_timers {}\n"
                + "reminder {text: what to remind, at: \"YYYY-MM-DDTHH:MM\" local time}\n"
                + "calendar_add {title, start: \"YYYY-MM-DDTHH:MM\", minutes?: duration}\n"
                + "calendar_read {day: \"today\"|\"tomorrow\"}\n"
                + "note_add {text} / notes_read {}\n"
                + "navigate {destination, mode?: drive|walk|bike}\n"
                + "map_search {query} - places nearby, e.g. \"petrol pump\"\n"
                + "weather {place?: city, omit for current location, day: \"today\"|\"tomorrow\"}\n"
                + "time_in {place}\n"
                + "flashlight {on: boolean}\n"
                + "volume {level?: 0-100, change?: up|down|mute|unmute|max}\n"
                + "brightness {level?: 0-100, change?: up|down|auto}\n"
                + "do_not_disturb {on: boolean}\n"
                + "ringer {mode: normal|vibrate|silent}\n"
                + "settings {page: wifi|bluetooth|mobile_data|airplane|hotspot|location|nfc|battery_saver|display|sound|apps|main}\n"
                + "camera {mode: photo|selfie|video}\n"
                + "translate {text, language: target language name in English}\n"
                + "web_search {query} - for live info you can't know (news, scores, prices, showtimes) or when the user asks to search.\n"
                + "battery {}\n\n"
                + "Rules:\n"
                + "- Prefer doing the action over talking about it. \"say\" for actions is a short confirmation like \"Calling Mom.\"\n"
                + "- If a message has no text yet, use action none and ask what to say. Keep the user's own words for message text.\n"
                + "- Resolve relative times (\"in 20 minutes\", \"tomorrow morning\" = 09:00, \"tonight\" = 20:00, \"evening\" = 18:00) into absolute local times.\n"
                + "- \"Wake me up at 7\" means 07:00. An alarm time without am/pm means the next time it comes round, preferring morning for wake-ups.\n"
                + "- Use the conversation so far for follow-ups like \"make it 8 instead\" or \"text her too\".\n"
                + "- For questions you can answer from knowledge, use answer with a direct, correct reply. Say when you're unsure. "
                + "Do not invent live data like today's news, scores, prices or weather: use web_search or weather.\n"
                + "- Never claim you did something the actions above can't do; say what you can't do instead.";
    }
}
