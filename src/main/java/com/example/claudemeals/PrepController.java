package com.example.claudemeals;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/week/prep")
public class PrepController {

    private final MealSlotRepository slots;
    private final RecipeRepository recipes;
    private final WeekService weeks;

    public PrepController(MealSlotRepository slots, RecipeRepository recipes, WeekService weeks) {
        this.slots = slots;
        this.recipes = recipes;
        this.weeks = weeks;
    }

    @GetMapping
    public PrepView prep(@RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        LocalDate periodStart = start == null ? weeks.currentPeriodStart() : start;
        List<MealSlot> week = slots.findByDateBetweenOrderByDateAsc(periodStart, periodStart.plusDays(6));
        week.sort(Comparator.comparing(MealSlot::getDate).thenComparing(s -> s.getMealType().ordinal()));

        Map<Long, Recipe> byId = new HashMap<>();
        for (Recipe r : recipes.findAll()) byId.put(r.getId(), r);

        // One entry per batch: the recipe plus the day it was made.
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        Map<String, Long> recipeOf = new LinkedHashMap<>();
        Map<String, LocalDate> cookedOn = new LinkedHashMap<>();
        int unplanned = 0;

        for (MealSlot s : week) {
            if (s.isHome() && s.getMealType().isRequired() && !s.isFilled()) unplanned++;

            if (s.getRecipeId() != null) {
                record(feeds, recipeOf, cookedOn, s.getRecipeId(),
                        s.getCookDate() == null ? s.getDate() : s.getCookDate(), s);
            }
            if (s.getSideRecipeId() != null) {
                record(feeds, recipeOf, cookedOn, s.getSideRecipeId(),
                        s.getSideCookDate() == null ? s.getDate() : s.getSideCookDate(), s);
            }
        }

        LocalDate today = LocalDate.now();
        Map<LocalDate, List<PrepView.Dish>> byCookDay = new TreeMap<>();
        List<PrepView.Dish> carriedIn = new ArrayList<>();

        for (String key : feeds.keySet()) {
            Recipe r = byId.get(recipeOf.get(key));
            if (r == null) continue;
            LocalDate cooked = cookedOn.get(key);
            List<String> meals = feeds.get(key);
            PrepView.Dish dish = new PrepView.Dish(r.getId(), r.getName(), r.isSide(),
                    meals.size(), meals, cooked, Words.dayName(cooked),
                    r.getImportedFrom() != null && !r.isVerified());

            // Anything made before the period began is already done — it isn't work.
            if (cooked.isBefore(periodStart)) carriedIn.add(dish);
            else byCookDay.computeIfAbsent(cooked, d -> new ArrayList<>()).add(dish);
        }
        carriedIn.sort(Comparator.comparing(PrepView.Dish::cookDate));

        List<PrepView.Session> sessions = byCookDay.entrySet().stream()
                .map(e -> new PrepView.Session(e.getKey(), Words.dayName(e.getKey()),
                        e.getKey().equals(today), e.getValue()))
                .toList();

        boolean planned = !sessions.isEmpty() || !carriedIn.isEmpty();
        return new PrepView(periodStart, planned, unplanned, sessions, carriedIn);
    }

    private void record(Map<String, List<String>> feeds, Map<String, Long> recipeOf,
                        Map<String, LocalDate> cookedOn, Long recipeId, LocalDate cooked,
                        MealSlot slot) {
        String key = recipeId + "@" + cooked;
        feeds.computeIfAbsent(key, k -> new ArrayList<>())
                .add(Words.dayName(slot.getDate()) + " " + slot.getMealType().getLabel().toLowerCase());
        recipeOf.put(key, recipeId);
        cookedOn.put(key, cooked);
    }
}
