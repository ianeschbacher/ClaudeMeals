package com.example.claudemeals;

/**
 * How a recipe fared after it was actually scheduled.
 *
 * {@code tier} drives planner preference — lower is picked first. SKIPPED carries no tier and
 * never becomes a recipe's standing rating: not cooking something says nothing about whether
 * it's any good, it's recorded so the week's history stays honest.
 */
public enum RecipeRating {
    UP("Thumbs up", "First picks each week", 0),
    MID("Thumbs middle", "Fine — fills gaps, or once the thumbs up run out", 2),
    DOWN("Thumbs down", "Last resort", 3);

    /** Tier for a recipe nobody has rated yet: worth trying, but after proven favourites. */
    public static final int UNRATED_TIER = 1;
    /** Tier that's only used when nothing else can cover the meal. */
    public static final int LAST_RESORT_TIER = 3;

    private final String label;
    private final String blurb;
    private final int tier;

    RecipeRating(String label, String blurb, int tier) {
        this.label = label;
        this.blurb = blurb;
        this.tier = tier;
    }

    public String getLabel() { return label; }
    public String getBlurb() { return blurb; }
    public int getTier() { return tier; }

    public static int tierOf(RecipeRating rating) {
        return rating == null ? UNRATED_TIER : rating.getTier();
    }
}
