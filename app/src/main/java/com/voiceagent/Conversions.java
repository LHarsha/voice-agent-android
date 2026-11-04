package com.voiceagent;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Convert 10 km to miles", "how many grams in a pound", "30 celsius in fahrenheit",
 * "100 dollars in rupees". Units are offline; currency uses the free open.er-api.com rates.
 */
public final class Conversions {

    private Conversions() {}

    private static final class Unit {
        final String name, plural, kind;
        final double factor; // to the base unit of its kind

        Unit(String name, String plural, String kind, double factor) {
            this.name = name;
            this.plural = plural;
            this.kind = kind;
            this.factor = factor;
        }
    }

    private static final Map<String, Unit> UNITS = new LinkedHashMap<>();
    private static final Map<String, String> CURRENCIES = new LinkedHashMap<>();

    private static void add(String kind, double factor, String name, String plural, String... aliases) {
        Unit u = new Unit(name, plural, kind, factor);
        UNITS.put(name, u);
        UNITS.put(plural, u);
        for (String a : aliases) UNITS.put(a, u);
    }

    static {
        // length (metres)
        add("length", 0.001, "millimeter", "millimeters", "mm", "millimetre", "millimetres");
        add("length", 0.01, "centimeter", "centimeters", "cm", "centimetre", "centimetres", "cms");
        add("length", 1, "meter", "meters", "m", "metre", "metres", "mtr");
        add("length", 1000, "kilometer", "kilometers", "km", "kilometre", "kilometres", "kms");
        add("length", 0.0254, "inch", "inches", "in");
        add("length", 0.3048, "foot", "feet", "ft");
        add("length", 0.9144, "yard", "yards", "yd");
        add("length", 1609.344, "mile", "miles", "mi");
        // mass (grams)
        add("mass", 0.001, "milligram", "milligrams", "mg");
        add("mass", 1, "gram", "grams", "g", "gm", "gms");
        add("mass", 1000, "kilogram", "kilograms", "kg", "kgs", "kilo", "kilos");
        add("mass", 28.349523125, "ounce", "ounces", "oz");
        add("mass", 453.59237, "pound", "pounds", "lb", "lbs");
        add("mass", 1_000_000, "tonne", "tonnes", "ton", "tons", "metric ton");
        // volume (millilitres)
        add("volume", 1, "milliliter", "milliliters", "ml", "millilitre", "millilitres");
        add("volume", 1000, "liter", "liters", "l", "litre", "litres", "ltr");
        add("volume", 236.588, "cup", "cups");
        add("volume", 473.176, "pint", "pints");
        add("volume", 3785.41, "gallon", "gallons", "gal");
        add("volume", 4.92892, "teaspoon", "teaspoons", "tsp");
        add("volume", 14.7868, "tablespoon", "tablespoons", "tbsp");
        // time (seconds)
        add("time", 1, "second", "seconds", "sec", "secs");
        add("time", 60, "minute", "minutes", "min", "mins");
        add("time", 3600, "hour", "hours", "hr", "hrs");
        add("time", 86400, "day", "days");
        add("time", 604800, "week", "weeks");
        add("time", 31_557_600, "year", "years");
        // speed (km/h)
        add("speed", 1, "kilometer per hour", "kilometers per hour", "kmph", "km/h", "kph", "kilometres per hour");
        add("speed", 1.609344, "mile per hour", "miles per hour", "mph");
        // data (bytes)
        add("data", 1e3, "kilobyte", "kilobytes", "kb");
        add("data", 1e6, "megabyte", "megabytes", "mb");
        add("data", 1e9, "gigabyte", "gigabytes", "gb");
        add("data", 1e12, "terabyte", "terabytes", "tb");
        // temperature handled specially
        add("temp", 0, "celsius", "celsius", "c", "degrees celsius", "degree celsius", "centigrade");
        add("temp", 0, "fahrenheit", "fahrenheit", "f", "degrees fahrenheit", "degree fahrenheit");
        add("temp", 0, "kelvin", "kelvin", "k");

        String[][] cur = {
                {"usd", "dollar", "dollars", "us dollar", "us dollars", "bucks", "usd"},
                {"inr", "rupee", "rupees", "indian rupee", "indian rupees", "rs", "inr"},
                {"eur", "euro", "euros", "eur"},
                {"gbp", "pound sterling", "british pound", "british pounds", "pounds sterling", "gbp", "quid"},
                {"aed", "dirham", "dirhams", "uae dirham", "aed"},
                {"jpy", "yen", "japanese yen", "jpy"},
                {"cny", "yuan", "chinese yuan", "renminbi", "cny"},
                {"sar", "riyal", "riyals", "saudi riyal", "sar"},
                {"sgd", "singapore dollar", "singapore dollars", "sgd"},
                {"aud", "australian dollar", "australian dollars", "aud"},
                {"cad", "canadian dollar", "canadian dollars", "cad"},
                {"myr", "ringgit", "malaysian ringgit", "myr"},
                {"npr", "nepali rupee", "nepalese rupees", "npr"},
                {"lkr", "sri lankan rupee", "sri lankan rupees", "lkr"},
                {"bdt", "taka", "bangladeshi taka", "bdt"},
                {"pkr", "pakistani rupee", "pakistani rupees", "pkr"},
                {"kwd", "kuwaiti dinar", "kwd"},
                {"qar", "qatari riyal", "qar"},
                {"chf", "swiss franc", "swiss francs", "chf"},
        };
        for (String[] c : cur) for (int i = 1; i < c.length; i++) CURRENCIES.put(c[i], c[0]);
    }

    private static final String NUM = "(\\d+(?:\\.\\d+)?|an?|one)";
    private static final Pattern CONVERT = Pattern.compile(
            "^(?:convert |change |what(?:'s| is) |how much is |how many \\S+(?: \\S+)? (?:is|are|in) )?" + NUM + "\\s*([a-z/ ]+?)\\s+(?:to|in|into|in to|equals|is how many|is how much|= ?)\\s+([a-z/ ]+?)$");
    private static final Pattern HOW_MANY = Pattern.compile(
            "^how many ([a-z/ ]+?) (?:are |is )?(?:in|per|make|make up) (?:a |an |one |1 )?" + "(\\d+(?:\\.\\d+)?)?\\s*([a-z/ ]+)$");

    /** Spoken answer for an offline unit conversion, or null if this isn't one. */
    public static String units(String s) {
        s = TimeParser.wordsToDigits(s).replaceAll("\\s*°\\s*", " degrees ").replaceAll("\\s+", " ").trim();
        Matcher m = CONVERT.matcher(s);
        if (m.find()) {
            Unit from = unit(m.group(2)), to = unit(m.group(3));
            if (from == null || to == null || !from.kind.equals(to.kind)) return null;
            return describe(num(m.group(1)), from, to);
        }
        m = HOW_MANY.matcher(s);
        if (m.find()) {
            Unit to = unit(m.group(1)), from = unit(m.group(3));
            if (from == null || to == null || !from.kind.equals(to.kind)) return null;
            double amount = m.group(2) != null ? Double.parseDouble(m.group(2)) : 1;
            return describe(amount, from, to);
        }
        return null;
    }

    /** If this is a currency question, returns {amount, fromCode, toCode, spokenFrom, spokenTo}. */
    public static Object[] currencyQuery(String s) {
        s = TimeParser.wordsToDigits(s).replaceAll("\\s+", " ").trim();
        s = s.replace("₹", " rupees ").replace("$", " dollars ").replace("€", " euros ").replace("£", " pound sterling ");
        s = s.replaceAll("\\s+", " ").trim();
        Matcher m = CONVERT.matcher(s);
        if (!m.find()) {
            Matcher hm = Pattern.compile("^how (?:many|much) ([a-z ]+?) (?:is|are|for|in|equals) " + NUM + " ?([a-z ]+)$").matcher(s);
            if (!hm.find()) return null;
            String to = currency(hm.group(1)), from = currency(hm.group(3));
            if (to == null || from == null) return null;
            return new Object[]{num(hm.group(2)), from, to, hm.group(3).trim(), hm.group(1).trim()};
        }
        String from = currency(m.group(2)), to = currency(m.group(3));
        // "pounds" is weight unless the other side is a currency
        if (from == null && m.group(2).trim().matches("pounds?") && to != null) from = "gbp";
        if (to == null && m.group(3).trim().matches("pounds?") && from != null) to = "gbp";
        if (from == null || to == null || from.equals(to)) return null;
        return new Object[]{num(m.group(1)), from, to, m.group(2).trim(), m.group(3).trim()};
    }

    /** Blocking network call. */
    public static String currency(Object[] q) throws Exception {
        double amount = (Double) q[0];
        String from = (String) q[1], to = (String) q[2];
        JSONObject o = new JSONObject(WebSkills.get("https://open.er-api.com/v6/latest/" + from.toUpperCase(Locale.ROOT)));
        if (!"success".equals(o.optString("result"))) return null;
        double rate = o.getJSONObject("rates").getDouble(to.toUpperCase(Locale.ROOT));
        return fmt(amount) + " " + from.toUpperCase(Locale.ROOT) + " is about " + money(amount * rate) + " "
                + to.toUpperCase(Locale.ROOT) + ".";
    }

    private static String currency(String spoken) {
        return CURRENCIES.get(spoken.trim().replaceAll("^(the|a|an)\\s+", ""));
    }

    private static Unit unit(String spoken) {
        String k = spoken.trim().replaceAll("^(the|a|an)\\s+", "").replaceAll("^degrees? ", "degrees ");
        Unit u = UNITS.get(k);
        if (u == null && k.startsWith("degrees ")) u = UNITS.get(k.substring(8));
        if (u == null && k.endsWith("s")) u = UNITS.get(k.substring(0, k.length() - 1));
        return u;
    }

    private static double num(String n) {
        return n.matches("an?|one") ? 1 : Double.parseDouble(n);
    }

    private static String describe(double amount, Unit from, Unit to) {
        double result;
        if (from.kind.equals("temp")) {
            double c = from.name.equals("celsius") ? amount : from.name.equals("fahrenheit") ? (amount - 32) * 5 / 9 : amount - 273.15;
            result = to.name.equals("celsius") ? c : to.name.equals("fahrenheit") ? c * 9 / 5 + 32 : c + 273.15;
            String unitTo = to.name.equals("kelvin") ? " kelvin" : " degrees " + cap(to.name);
            String unitFrom = from.name.equals("kelvin") ? " kelvin" : " degrees " + cap(from.name);
            return fmt(amount) + unitFrom + " is " + fmt(result) + unitTo + ".";
        }
        result = amount * from.factor / to.factor;
        return fmt(amount) + " " + (amount == 1 ? from.name : from.plural) + " is " + fmt(result) + " "
                + (Math.abs(result - 1) < 1e-9 ? to.name : to.plural) + ".";
    }

    static String fmt(double v) {
        double a = Math.abs(v);
        if (a != 0 && (a < 0.01 || a >= 1e9)) return String.format(Locale.US, "%.3g", v);
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String r = String.format(Locale.US, a < 1 ? "%.3f" : a < 100 ? "%.2f" : "%.1f", v);
        return r.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String money(double v) {
        return String.format(Locale.US, v >= 100 ? "%,.0f" : "%,.2f", v);
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
