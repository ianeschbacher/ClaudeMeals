package com.example.claudemeals;

import java.time.LocalDate;
import java.util.List;

/**
 * Where the Daily Dozen is falling short — across the planned week, and across the recipe
 * library as a whole. The library half is the actionable one: it says what kind of recipe
 * is missing, not just how many meals are unfilled.
 */
public record DozenGapView(LocalDate start, List<ItemGap> items, List<String> advice) {

    /**
     * @param daysMet          days in the week that reached the daily target
     * @param recipesCovering  how many active recipes tick this item at all
     * @param recipesReachable how many of those fit a meal slot that's actually switched on —
     *                         a recipe for a slot you've turned off can never be scheduled
     * @param blockedMeals     meal slots those unreachable recipes need, but that are off
     */
    public record ItemGap(
            String item,
            String label,
            int target,
            int daysMet,
            int recipesCovering,
            int recipesReachable,
            List<String> blockedMeals,
            String verdict
    ) {}
}
