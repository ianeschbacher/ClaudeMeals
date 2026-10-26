package com.example.claudemeals;

import java.time.LocalDate;
import java.util.List;

/** Everything the week screen renders, in one payload. */
public record WeekView(
        LocalDate start,
        List<Day> days,
        Gaps gaps
) {
    public record Day(
            LocalDate date,
            String dayName,
            List<Slot> slots,
            List<DozenProgress> dozen,
            int dozenHit,       // how many recipe-driven dozen items met their target
            int dozenPossible   // how many could be met (10 - beverages/exercise excluded)
    ) {}

    public record Slot(
            Long id,
            String mealType,
            String mealLabel,
            boolean required,
            boolean home,
            boolean optOut,
            Long recipeId,
            String recipeName,
            Long sideRecipeId,
            String sideName,
            boolean sideLeftover,
            boolean leftover,
            boolean pinned
    ) {}

    public record DozenProgress(String item, String label, int count, int target, boolean met) {}

    /** Required home meals the planner couldn't cover — what the "add recipes" prompt is built from. */
    public record Gaps(int total, List<String> byMeal) {}
}
