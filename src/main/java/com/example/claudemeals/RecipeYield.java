package com.example.claudemeals;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * A batch of servings one cook of a recipe produces.
 *
 * The servings are shared across every meal type listed: a yield of 2 servings for
 * {BREAKFAST, SNACK} means two yogurt bowls, each eaten as *either* a breakfast or a snack —
 * spending one on breakfast leaves one for a snack. To say a recipe makes a dinner *and*
 * two lunches of leftovers, use two separate yields.
 *
 * Stored as a comma-joined string so it stays a plain embeddable (JPA can't nest a collection
 * inside an element collection).
 */
@Embeddable
public class RecipeYield {

    @Column(name = "meal_types")
    private String mealTypes;

    private int servings;

    protected RecipeYield() {}

    public RecipeYield(List<MealType> mealTypes, int servings) {
        setMealTypes(mealTypes);
        this.servings = servings;
    }

    public List<MealType> getMealTypes() {
        if (mealTypes == null || mealTypes.isBlank()) return List.of();
        List<MealType> out = new ArrayList<>();
        for (String part : mealTypes.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) out.add(MealType.valueOf(name));
        }
        return out;
    }

    public void setMealTypes(List<MealType> types) {
        if (types == null || types.isEmpty()) {
            this.mealTypes = null;
            return;
        }
        // LinkedHashSet: drop duplicates, keep the order the user picked.
        this.mealTypes = String.join(",", new LinkedHashSet<>(types).stream().map(Enum::name).toList());
    }

    @JsonIgnore
    public String getMealTypesRaw() { return mealTypes; }

    public int getServings() { return servings; }
    public void setServings(int servings) { this.servings = servings; }

    public boolean covers(MealType type) {
        return getMealTypes().contains(type);
    }

    /** True once this yield names at least one meal and produces at least one serving. */
    @JsonIgnore
    public boolean isUsable() {
        return servings > 0 && !getMealTypes().isEmpty();
    }

    /** "2 breakfasts or snacks" — serialized so the UI doesn't rebuild this phrasing. */
    public String getSummary() {
        List<MealType> types = getMealTypes();
        if (types.isEmpty() || servings <= 0) return "";
        List<String> labels = types.stream()
                .map(t -> Words.plural(t.getLabel().toLowerCase(), servings)).toList();
        return servings + " " + String.join(" or ", labels);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RecipeYield other)) return false;
        return servings == other.servings && Objects.equals(mealTypes, other.mealTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mealTypes, servings);
    }
}
