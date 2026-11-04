package com.voiceagent;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Understands spoken times ("7:30 pm", "noon", "tomorrow at 9") and durations ("1 hour 20 minutes"). */
public final class TimeParser {

    private TimeParser() {}

    private static final Map<String, Integer> NUMBER_WORDS = new HashMap<>();

    static {
        String[] ones = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
                "nineteen", "twenty"};
        for (int i = 0; i < ones.length; i++) NUMBER_WORDS.put(ones[i], i);
        NUMBER_WORDS.put("thirty", 30);
        NUMBER_WORDS.put("forty", 40);
        NUMBER_WORDS.put("fifty", 50);
        NUMBER_WORDS.put("sixty", 60);
        NUMBER_WORDS.put("ninety", 90);
        NUMBER_WORDS.put("a", 1);
        NUMBER_WORDS.put("an", 1);
        NUMBER_WORDS.put("a couple of", 2);
        NUMBER_WORDS.put("a few", 3);
    }

    /** Replace small number words with digits ("twenty five" -> "25"). */
    public static String wordsToDigits(String s) {
        s = s.replaceAll("\\b(twenty|thirty|forty|fifty)[ -](one|two|three|four|five|six|seven|eight|nine)\\b",
                "$1+$2");
        Matcher m = Pattern.compile("\\b(twenty|thirty|forty|fifty)\\+(one|two|three|four|five|six|seven|eight|nine)\\b")
                .matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, String.valueOf(NUMBER_WORDS.get(m.group(1)) + NUMBER_WORDS.get(m.group(2))));
        }
        m.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("\\ba couple of\\b", "2").replaceAll("\\ba few\\b", "3");
        for (Map.Entry<String, Integer> e : NUMBER_WORDS.entrySet()) {
            String k = e.getKey();
            if (k.equals("a") || k.equals("an") || k.contains(" ")) continue;
            s = s.replaceAll("\\b" + k + "\\b", String.valueOf(e.getValue()));
        }
        return s;
    }

    /** Clean up recognizer output like "7:30 p.m." -> "7:30 pm". */
    public static String normalize(String s) {
        s = s.toLowerCase();
        s = s.replaceAll("a\\.\\s?m\\.?", "am").replaceAll("p\\.\\s?m\\.?", "pm");
        s = s.replaceAll("\\b(\\d{1,2})(\\d{2})\\s*(am|pm)\\b", "$1:$2 $3");       // "730 am" -> "7:30 am"
        s = s.replaceAll("(\\d)\\s*(am|pm)\\b", "$1 $2");
        s = s.replaceAll("(\\d)-(minutes?|mins?|hours?|hrs?|seconds?|secs?|days?|weeks?)\\b", "$1 $2"); // "5-minute"
        s = s.replaceAll("\\b(\\d+)(minutes?|mins?|hours?|hrs?|seconds?|secs?)\\b", "$1 $2");          // "10min"
        s = s.replaceAll("o'clock", "");
        return s;
    }

    // ---------------------------------------------------------------- durations

    private static final Pattern DURATION_PART = Pattern.compile(
            "(\\d+(?:\\.\\d+)?|an?)\\s*(?:and a half\\s*)?(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b(\\s*and a half)?");

    /** Total seconds in a phrase, or -1 if no duration is mentioned. */
    public static int parseDurationSeconds(String text) {
        String s = wordsToDigits(normalize(text));
        double total = 0;
        boolean found = false;
        if (s.matches(".*\\bhalf an? hour\\b.*")) {
            total += 1800;
            found = true;
            s = s.replaceAll("\\bhalf an? hour\\b", "");
        }
        if (s.matches(".*\\b(quarter of an hour|quarter hour)\\b.*")) {
            total += 900;
            found = true;
            s = s.replaceAll("\\b(quarter of an hour|quarter hour)\\b", "");
        }
        Matcher m = DURATION_PART.matcher(s);
        while (m.find()) {
            String n = m.group(1);
            double v = n.startsWith("a") ? 1 : Double.parseDouble(n);
            boolean half = m.group(0).contains("and a half");
            if (half) v += 0.5;
            String unit = m.group(2);
            if (unit.startsWith("h")) total += v * 3600;
            else if (unit.startsWith("m")) total += v * 60;
            else total += v;
            found = true;
        }
        return found ? (int) Math.round(total) : -1;
    }

    /** "1 hour 5 minutes" style text for a number of seconds. */
    public static String describeDuration(int seconds) {
        int h = seconds / 3600, m = (seconds % 3600) / 60, sec = seconds % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(h == 1 ? " hour" : " hours");
        if (m > 0) b.append(b.length() > 0 ? " " : "").append(m).append(m == 1 ? " minute" : " minutes");
        if (sec > 0) b.append(b.length() > 0 ? " " : "").append(sec).append(sec == 1 ? " second" : " seconds");
        return b.length() == 0 ? "0 seconds" : b.toString();
    }

    // ---------------------------------------------------------------- clock times

    private static final Pattern CLOCK = Pattern.compile(
            "\\b(\\d{1,2})(?:[:.](\\d{2})|\\s(\\d{2})(?=\\s*(?:am|pm)\\b))?\\s*(am|pm|in the morning|in the evening|at night|in the afternoon|tonight)?\\b");

    /**
     * Find a clock time in the text. Returns {hour24, minute, explicitAmPm(0/1)} or null.
     */
    public static int[] parseClock(String text) {
        String s = wordsToDigits(normalize(text));
        if (s.matches(".*\\bnoon\\b.*") || s.contains("midday")) return new int[]{12, 0, 1};
        if (s.matches(".*\\bmidnight\\b.*")) return new int[]{0, 0, 1};

        Matcher half = Pattern.compile("\\bhalf past (\\d{1,2})\\b").matcher(s);
        if (half.find()) return withSuffix(Integer.parseInt(half.group(1)), 30, s);
        Matcher qp = Pattern.compile("\\bquarter past (\\d{1,2})\\b").matcher(s);
        if (qp.find()) return withSuffix(Integer.parseInt(qp.group(1)), 15, s);
        Matcher qt = Pattern.compile("\\bquarter to (\\d{1,2})\\b").matcher(s);
        if (qt.find()) {
            int hh = Integer.parseInt(qt.group(1)) - 1;
            return withSuffix(hh <= 0 ? 12 : hh, 45, s);
        }

        // Pick the most time-like number: explicit am/pm first, then "at 7", then "for/to 7",
        // then "7:30". Numbers followed by a duration unit ("2 hours") are never clock times.
        String noDur = s.replaceAll("\\b\\d+(?:\\.\\d+)?\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|days?|weeks?|percent|%)", " ");
        String tail = "\\s*(?:am|pm|in the morning|in the evening|at night|in the afternoon|tonight)?";
        String num = "\\d{1,2}(?:[:.]\\d{2})?(?:\\s\\d{2}(?=\\s*(?:am|pm)))?";
        String[] patterns = {
                "\\b(\\d{1,2}(?:[:.]\\d{2})?(?:\\s\\d{2})?\\s*(?:am|pm))\\b",
                "\\b(?:at|by|around)\\s+(" + num + tail + ")",
                "\\b(?:for|to|till|until)\\s+(" + num + tail + ")",
                "\\b(\\d{1,2}[:.]\\d{2})\\b",
        };
        String candidate = null;
        for (String p : patterns) {
            Matcher cm = Pattern.compile(p).matcher(noDur);
            if (cm.find()) {
                candidate = cm.group(1);
                break;
            }
        }
        if (candidate == null) return null;
        Matcher m = CLOCK.matcher(candidate.trim());
        if (!m.find()) return null;
        int hour = Integer.parseInt(m.group(1));
        String mm = m.group(2) != null ? m.group(2) : m.group(3);
        int minute = mm != null ? Integer.parseInt(mm) : 0;
        if (hour > 23 || minute > 59) return null;
        String suffix = m.group(4);
        if (suffix == null && s.matches(".*\\b(tonight|this evening|in the evening|at night)\\b.*")) suffix = "pm";
        if (suffix == null && s.matches(".*\\b(in the morning|this morning)\\b.*")) suffix = "am";
        if (suffix == null && s.matches(".*\\b(this afternoon|in the afternoon)\\b.*")) suffix = "pm";
        return applySuffix(hour, minute, suffix);
    }

    private static int[] withSuffix(int hour, int minute, String s) {
        String suffix = null;
        if (s.matches(".*\\bpm\\b.*") || s.matches(".*\\b(tonight|evening|at night|afternoon)\\b.*")) suffix = "pm";
        else if (s.matches(".*\\bam\\b.*") || s.contains("morning")) suffix = "am";
        return applySuffix(hour, minute, suffix);
    }

    private static int[] applySuffix(int hour, int minute, String suffix) {
        if (suffix == null) return new int[]{hour, minute, hour > 12 || hour == 0 ? 1 : 0};
        boolean pm = suffix.equals("pm") || suffix.contains("evening") || suffix.contains("night")
                || suffix.contains("afternoon") || suffix.equals("tonight");
        if (hour > 12) return new int[]{hour, minute, 1};
        if (pm && hour < 12) hour += 12;
        if (!pm && hour == 12) hour = 0;
        return new int[]{hour, minute, 1};
    }

    /**
     * Next moment matching the clock time. If am/pm wasn't said, picks whichever
     * of the two candidates comes first. Honors "tomorrow" / weekday names.
     */
    public static Calendar nextOccurrence(int[] clock, String text) {
        String s = normalize(text);
        Calendar now = Calendar.getInstance();
        Calendar best = null;
        int[] hours = clock[2] == 1 ? new int[]{clock[0]} : new int[]{clock[0] % 12, clock[0] % 12 + 12};
        int dayOffset = s.matches(".*\\btomorrow\\b.*") ? 1 : 0;
        int weekday = weekdayIn(s);
        for (int h : hours) {
            Calendar c = (Calendar) now.clone();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, clock[1]);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (weekday > 0) {
                int diff = (weekday - c.get(Calendar.DAY_OF_WEEK) + 7) % 7;
                c.add(Calendar.DAY_OF_MONTH, diff);
                if (!c.after(now)) c.add(Calendar.DAY_OF_MONTH, 7);
            } else if (dayOffset > 0) {
                c.add(Calendar.DAY_OF_MONTH, dayOffset);
            } else if (!c.after(now)) {
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
            if (best == null || c.before(best)) best = c;
        }
        // With "tomorrow" and no am/pm, prefer the morning-ish/daytime reading (7 -> 7am, 3 -> 3pm).
        if (clock[2] == 0 && (dayOffset > 0 || weekday > 0)) {
            int h = clock[0] % 12;
            int chosen = (h >= 7 && h <= 11) ? h : h + 12;
            if (h == 0) chosen = 12;
            best.set(Calendar.HOUR_OF_DAY, chosen);
        }
        return best;
    }

    private static int weekdayIn(String s) {
        String[] days = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
        for (int i = 0; i < days.length; i++) {
            if (s.matches(".*\\b(on |next |this )?" + days[i] + "\\b.*")) return i + 1;
        }
        return 0;
    }

    public static String speakTime(int hour24, int minute) {
        int h = hour24 % 12 == 0 ? 12 : hour24 % 12;
        String ampm = hour24 < 12 ? "AM" : "PM";
        return minute == 0 ? h + " " + ampm : String.format(java.util.Locale.US, "%d:%02d %s", h, minute, ampm);
    }

    public static String speakWhen(Calendar c) {
        Calendar now = Calendar.getInstance();
        String time = speakTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        if (sameDay(c, now)) return time + " today";
        if (sameDay(c, tomorrow)) return time + " tomorrow";
        return time + " on " + c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.getDefault());
    }

    static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
