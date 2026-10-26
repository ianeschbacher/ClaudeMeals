package com.example.claudemeals;

/** The five slots in a day. Breakfast/lunch/dinner are required; smoothie/snack are opt-in. */
public enum MealType {
    BREAKFAST("Breakfast", true),
    LUNCH("Lunch", true),
    DINNER("Dinner", true),
    SMOOTHIE("Smoothie", false),
    SNACK("Snack", false);

    private final String label;
    private final boolean required;

    MealType(String label, boolean required) {
        this.label = label;
        this.required = required;
    }

    public String getLabel() { return label; }
    /** True for the meals the week must account for; false for the optional extra slots. */
    public boolean isRequired() { return required; }
}
