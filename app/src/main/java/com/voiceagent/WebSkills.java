package com.voiceagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Free, key-less web services. All methods block, so call them off the main thread.
 *  - Wikipedia REST API for "who/what is ..."
 *  - dictionaryapi.dev for "define ..."
 *  - Open-Meteo for weather + city lookup
 */
public final class WebSkills {

    private WebSkills() {}

    private static final String USER_AGENT = "VoiceAgentAndroid/1.0 (personal assistant app)";

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ Wikipedia

    /** Short spoken summary of a topic, or null if nothing useful was found. */
    public static String wikipedia(String topic) throws IOException {
        String lang = "en";
        JSONObject search;
        try {
            search = new JSONObject(get("https://" + lang + ".wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch="
                    + enc(topic)));
            JSONArray hits = search.getJSONObject("query").getJSONArray("search");
            if (hits.length() == 0) return null;
            String title = hits.getJSONObject(0).getString("title");
            JSONObject summary = new JSONObject(get("https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
                    + enc(title.replace(' ', '_')).replace("+", "%20")));
            if ("disambiguation".equals(summary.optString("type"))) return null;
            String extract = summary.optString("extract", "").trim();
            if (extract.isEmpty()) return null;
            return firstSentences(extract, 2);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    static String firstSentences(String text, int count) {
        // Split on ". " that follows a lowercase letter/digit/closing bracket, to avoid "U.S." etc.
        String[] parts = text.split("(?<=[a-z0-9)\\]][.!?])\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length && i < count; i++) {
            if (b.length() > 0) b.append(' ');
            b.append(parts[i]);
        }
        String r = b.toString();
        return r.length() > 450 ? r.substring(0, 450) + "…" : r;
    }

    // ------------------------------------------------------------------ Dictionary

    public static String define(String word) throws IOException {
        try {
            JSONArray arr = new JSONArray(get("https://api.dictionaryapi.dev/api/v2/entries/en/" + enc(word.trim())));
            JSONObject entry = arr.getJSONObject(0);
            JSONObject meaning = entry.getJSONArray("meanings").getJSONObject(0);
            String pos = meaning.optString("partOfSpeech");
            String def = meaning.getJSONArray("definitions").getJSONObject(0).getString("definition");
            return capitalize(word.trim()) + (pos.isEmpty() ? "" : ", " + pos) + ": " + def;
        } catch (IOException | org.json.JSONException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Weather

    /** Returns {lat, lon, "Place name"} or null. */
    public static Object[] geocode(String place) throws IOException {
        try {
            JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                    + enc(place.trim())));
            JSONArray r = o.optJSONArray("results");
            if (r == null || r.length() == 0) return null;
            JSONObject p = r.getJSONObject(0);
            return new Object[]{p.getDouble("latitude"), p.getDouble("longitude"), p.getString("name")};
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /**
     * @param when 0 = now/today, 1 = tomorrow
     */
    public static String weather(double lat, double lon, String placeName, int when, boolean fahrenheit)
            throws IOException {
        String unit = fahrenheit ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "";
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=2%s", lat, lon, unit);
        try {
            JSONObject o = new JSONObject(get(url));
            JSONObject daily = o.getJSONObject("daily");
            int hi = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(when));
            int lo = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(when));
            int rain = daily.getJSONArray("precipitation_probability_max").optInt(when, 0);
            String deg = fahrenheit ? "°F" : "°C";
            String where = placeName == null ? "" : " in " + placeName;
            if (when == 0) {
                JSONObject cur = o.getJSONObject("current");
                int t = (int) Math.round(cur.getDouble("temperature_2m"));
                int feels = (int) Math.round(cur.getDouble("apparent_temperature"));
                String desc = describeCode(cur.getInt("weather_code"));
                StringBuilder b = new StringBuilder();
                b.append("It's ").append(t).append(deg).append(" and ").append(desc).append(where).append(" right now");
                if (Math.abs(feels - t) >= 3) b.append(", feeling like ").append(feels).append(deg);
                b.append(". Today's high is ").append(hi).append(deg).append(" with a low of ").append(lo).append(deg).append('.');
                if (rain >= 30) b.append(" There's a ").append(rain).append("% chance of rain.");
                return b.toString();
            } else {
                String desc = describeCode(daily.getJSONArray("weather_code").getInt(1));
                String b = "Tomorrow" + where + " expect " + desc + ", with a high of " + hi + deg + " and a low of " + lo + deg + ".";
                if (rain >= 30) b += " Chance of rain is " + rain + "%.";
                return b;
            }
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    static String describeCode(int code) {
        if (code == 0) return "clear skies";
        if (code == 1) return "mostly clear";
        if (code == 2) return "partly cloudy";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "drizzly";
        if (code >= 61 && code <= 67) return "rainy";
        if (code >= 71 && code <= 77) return "snowy";
        if (code >= 80 && code <= 82) return "showery";
        if (code == 85 || code == 86) return "snow showers";
        if (code >= 95) return "stormy";
        return "mixed weather";
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
