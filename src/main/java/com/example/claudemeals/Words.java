package com.example.claudemeals;

/** Small text helpers shared by the API's human-readable phrasing. */
final class Words {

    private Words() {}

    /** "Mon", "Tue" — the short day name used everywhere the UI shows a week. */
    static String dayName(java.time.LocalDate date) {
        return date.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.SHORT, java.util.Locale.US);
    }

    /** "lunch" -> "lunches", "dinner" -> "dinners", left alone when n is 1. */
    static String plural(String word, int n) {
        if (n == 1) return word;
        return word.endsWith("ch") || word.endsWith("sh") || word.endsWith("s") ? word + "es" : word + "s";
    }
}
