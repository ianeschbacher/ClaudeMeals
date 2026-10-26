package com.example.claudemeals;

/**
 * Dr. Greger's Daily Dozen. Targets are servings per day.
 * Beverages and exercise aren't driven by recipes, so they're excluded from plan scoring.
 */
public enum DozenItem {
    BEANS("Beans", 3, true),
    BERRIES("Berries", 1, true),
    OTHER_FRUITS("Other Fruits", 3, true),
    CRUCIFEROUS("Cruciferous Vegetables", 1, true),
    GREENS("Greens", 2, true),
    OTHER_VEGETABLES("Other Vegetables", 2, true),
    FLAXSEEDS("Flaxseeds", 1, true),
    NUTS_AND_SEEDS("Nuts & Seeds", 1, true),
    HERBS_AND_SPICES("Herbs & Spices", 1, true),
    WHOLE_GRAINS("Whole Grains", 3, true),
    BEVERAGES("Beverages", 5, false),
    EXERCISE("Exercise", 1, false);

    private final String label;
    private final int dailyTarget;
    private final boolean fromRecipes;

    DozenItem(String label, int dailyTarget, boolean fromRecipes) {
        this.label = label;
        this.dailyTarget = dailyTarget;
        this.fromRecipes = fromRecipes;
    }

    public String getLabel() { return label; }
    public int getDailyTarget() { return dailyTarget; }
    /** False for the two items a recipe can't tick off (beverages, exercise). */
    public boolean isFromRecipes() { return fromRecipes; }
}
