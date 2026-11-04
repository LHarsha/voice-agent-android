package com.voiceagent;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import java.util.Locale;

/** Finds the best matching contact for a spoken name ("mom", "john smith", "dr patel"). */
public final class Contacts {

    private Contacts() {}

    public static final class Match {
        public final String name;
        public final String number;
        public final int score;

        Match(String name, String number, int score) {
            this.name = name;
            this.number = number;
            this.score = score;
        }
    }

    /** @return best match or null. Score 100 = exact, lower = fuzzier. */
    /** Family words people save contacts under, in several Indian languages too. */
    private static final String[][] RELATIONS = {
            {"mom", "mum", "mother", "mummy", "mommy", "mama", "amma", "maa", "ma", "ammi", "aai", "mumma", "mom mobile"},
            {"dad", "father", "daddy", "papa", "pappa", "appa", "nanna", "baba", "abba", "pitaji", "daddy mobile", "dad mobile"},
            {"brother", "bro", "bhai", "bhaiya", "anna", "annayya", "dada", "thambi", "tammudu"},
            {"sister", "sis", "didi", "akka", "behen", "chechi", "thangachi", "chelli"},
            {"wife", "wifey", "my wife", "biwi", "darling"},
            {"husband", "hubby", "my husband"},
            {"grandma", "grandmother", "nani", "dadi", "ammamma", "nanamma", "paati", "ajji"},
            {"grandpa", "grandfather", "nana", "dada ji", "thatha", "tata", "ajja"},
            {"uncle", "mama ji", "chacha", "mamaya", "babai", "chithappa"},
            {"aunty", "aunt", "auntie", "chachi", "mami", "atta", "pinni", "chithi"},
    };

    public static Match find(Context ctx, String spoken) {
        String q = clean(spoken);
        if (q.isEmpty()) return null;
        java.util.List<String> queries = new java.util.ArrayList<>();
        queries.add(q);
        for (String[] group : RELATIONS) {
            for (String w : group) {
                if (w.equals(q)) {
                    for (String alt : group) if (!alt.equals(q)) queries.add(alt);
                    break;
                }
            }
        }
        String[] proj = {Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY};
        Match best = null;
        int bestRank = -1;
        try (Cursor c = ctx.getContentResolver().query(Phone.CONTENT_URI, proj, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String name = c.getString(0);
                String number = c.getString(1);
                if (name == null || number == null) continue;
                String cn = clean(name);
                int score = 0;
                for (int k = 0; k < queries.size(); k++) {
                    // The words you actually said count a little more than relation aliases.
                    int sc = score(queries.get(k), cn) - (k == 0 ? 0 : 5);
                    if (sc > score) score = sc;
                }
                if (score <= 0) continue;
                // Prefer the primary / mobile number of the same person.
                int bonus = c.getInt(3) > 0 ? 3 : (c.getInt(2) == Phone.TYPE_MOBILE ? 2 : 0);
                int rank = score * 10 + bonus;
                if (rank > bestRank) {
                    bestRank = rank;
                    best = new Match(name, number, score);
                }
            }
        } catch (SecurityException e) {
            return null;
        }
        // Too weak a match is worse than none: never ring the wrong person.
        return best != null && best.score >= 40 ? best : null;
    }

    static String clean(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("['’]s\\b", "")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\b(my|the)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static int score(String q, String name) {
        if (name.isEmpty()) return 0;
        if (name.equals(q)) return 100;
        if (name.startsWith(q + " ")) return 85;          // "john" -> "john smith"
        if (name.endsWith(" " + q)) return 70;            // "smith" -> "john smith"
        if (name.contains(" " + q + " ")) return 65;
        String[] qw = q.split(" ");
        boolean all = true;
        for (String w : qw) if (!(" " + name + " ").contains(" " + w + " ")) all = false;
        if (all) return 60;
        if (name.startsWith(q)) return 50;
        // Sounds-alike: "preeya" ~ "priya", "shreya" ~ "sreya", "mohammad" ~ "muhammed"
        String pq = phonetic(q);
        if (pq.equals(phonetic(name))) return 80;
        for (String token : name.split(" ")) {
            if (token.length() >= 3 && pq.equals(phonetic(token))) return 72;
        }
        double sim = Apps.similarity(q, name);
        if (sim >= 0.8) return (int) (sim * 50);
        String first = name.split(" ")[0];
        double simFirst = Apps.similarity(q, first);
        if (simFirst >= 0.75) return (int) (simFirst * 45);
        double simPh = Apps.similarity(pq, phonetic(first));
        if (pq.length() >= 3 && simPh >= 0.75) return (int) (simPh * 40);
        return 0;
    }

    /** A rough "how it sounds" key that ignores common spelling differences in names. */
    static String phonetic(String s) {
        String p = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "");
        p = p.replace("ph", "f").replace("sh", "s").replace("ch", "c").replace("th", "t").replace("dh", "d")
                .replace("bh", "b").replace("kh", "k").replace("gh", "g").replace("ck", "k").replace("q", "k")
                .replace("x", "ks").replace("z", "s").replace("w", "v").replace("ee", "i").replace("oo", "u")
                .replace("y", "i").replace("ou", "u").replace("au", "o");
        p = p.replaceAll("([a-z])\\1+", "$1");          // double letters
        p = p.replaceAll("(?<=[a-z])h", "");             // silent h after a letter
        p = p.replaceAll("(?<=[a-z])[aeiou]+(?=[a-z])", "a"); // inner vowels blur together
        p = p.replaceAll("[aeiou]+$", "a");               // ending vowel: priya / priyaa / preeya
        return p.trim();
    }
}
