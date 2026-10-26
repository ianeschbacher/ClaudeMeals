package com.example.claudemeals;

import java.time.LocalDate;
import java.util.List;

/**
 * The period's cooking, organised the way it actually happens: one entry per batch, grouped
 * under the day you make it. Grouping by first appearance made a day of leftovers read as a
 * day of cooking, which is the opposite of what a prep list is for.
 *
 * @param carriedIn batches cooked before this period started — already in the fridge, nothing
 *                  to do but eat them
 */
public record PrepView(
        LocalDate start,
        boolean planned,
        int unplannedMeals,
        List<Session> sessions,
        List<Dish> carriedIn
) {
    /** One day's cooking. */
    public record Session(LocalDate date, String dayName, boolean today, List<Dish> dishes) {}

    /**
     * @param meals how many meals this one batch covers inside the period
     * @param feeds those meals, in order, e.g. "Thu lunch"
     */
    public record Dish(
            Long recipeId,
            String name,
            boolean side,
            int meals,
            List<String> feeds,
            LocalDate cookDate,
            String cookDay,
            boolean unverified
    ) {}
}
