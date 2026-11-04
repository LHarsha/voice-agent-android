package com.voiceagent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Turns spoken math ("15 percent of 80", "square root of 144", "7 times 6") into an answer. */
public final class MathEval {

    private MathEval() {}

    /** Returns a spoken answer, or null if the text isn't math. */
    public static String tryAnswer(String text) {
        String s = TimeParser.wordsToDigits(text.toLowerCase())
                .replaceAll("^(what is|what's|whats|calculate|compute|how much is|solve|tell me)\\s+", "")
                .replaceAll("[?!]", "")
                .replace(",", "")
                .trim();
        if (!s.matches(".*\\d.*")) return null;

        s = s.replaceAll("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s+of\\s+(\\d+(?:\\.\\d+)?)", "($1/100*$2)");
        s = s.replaceAll("square root of\\s*(\\d+(?:\\.\\d+)?)", "sqrt($1)");
        s = s.replaceAll("cube root of\\s*(\\d+(?:\\.\\d+)?)", "cbrt($1)");
        s = s.replaceAll("\\bsquared\\b", "^2").replaceAll("\\bcubed\\b", "^3");
        s = s.replaceAll("to the power of|raised to( the)?|to the", "^");
        s = s.replaceAll("multiplied by|times|\\binto\\b|\\bx\\b|×", "*");
        s = s.replaceAll("divided by|over|÷", "/");
        s = s.replaceAll("\\bplus\\b|\\band\\b", "+");
        s = s.replaceAll("\\bminus\\b|\\bless\\b|−", "-");
        s = s.replaceAll("\\bmod(ulo)?\\b", "%");
        s = s.replaceAll("\\s+", "");

        if (!s.matches("[0-9.+\\-*/^%()sqrtcb]+")) return null;
        if (!s.matches(".*([+\\-*/^%]|sqrt|cbrt).*")) return null;
        try {
            Parser p = new Parser(s);
            double v = p.parseExpression();
            if (p.pos != s.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
            return format(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String format(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return String.valueOf((long) Math.rint(v));
        return new BigDecimal(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        double parseExpression() {
            double v = parseTerm();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') { pos++; v += parseTerm(); }
                else if (c == '-') { pos++; v -= parseTerm(); }
                else break;
            }
            return v;
        }

        double parseTerm() {
            double v = parseFactor();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*') { pos++; v *= parseFactor(); }
                else if (c == '/') { pos++; v /= parseFactor(); }
                else if (c == '%') { pos++; v %= parseFactor(); }
                else break;
            }
            return v;
        }

        double parseFactor() {
            double base = parseUnary();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                return Math.pow(base, parseFactor());
            }
            return base;
        }

        double parseUnary() {
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -parseUnary(); }
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return parseUnary(); }
            return parsePrimary();
        }

        double parsePrimary() {
            if (s.startsWith("sqrt(", pos)) { pos += 4; return Math.sqrt(parseParen()); }
            if (s.startsWith("cbrt(", pos)) { pos += 4; return Math.cbrt(parseParen()); }
            if (pos < s.length() && s.charAt(pos) == '(') return parseParen();
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (start == pos) throw new IllegalArgumentException("number expected");
            return Double.parseDouble(s.substring(start, pos));
        }

        double parseParen() {
            if (s.charAt(pos) != '(') throw new IllegalArgumentException("( expected");
            pos++;
            double v = parseExpression();
            if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException(") expected");
            pos++;
            return v;
        }
    }
}
