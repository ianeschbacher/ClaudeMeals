package com.example.claudemeals;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class DozenGapService {

    private final MealSlotRepository slots;
    private final RecipeRepository recipes;

    public DozenGapService(MealSlotRepository slots, RecipeRepository recipes) {
        this.slots = slots;
        this.recipes = recipes;
    }

    public DozenGapView analyse(LocalDate start) {
        LocalDate end = start.plusDays(6);
        List<Recipe> library = recipes.findByActiveTrueOrderByNameAsc();
        Map<Long, Recipe> byId = new HashMap<>();
        for (Recipe r : library) byId.put(r.getId(), r);

        // Which meal slots are actually switched on this week. A recipe that only fits a slot
        // you've turned off can never be scheduled, however good its Daily Dozen numbers are.
        Set<MealType> openMeals = EnumSet.noneOf(MealType.class);
        for (MealSlot s : slots.findByDateBetweenOrderByDateAsc(start, end)) {
            if (s.isHome()) openMeals.add(s.getMealType());
        }

        // Servings per item per day across the planned week.
        Map<LocalDate, Map<DozenItem, Integer>> byDay = new HashMap<>();
        for (MealSlot s : slots.findByDateBetweenOrderByDateAsc(start, end)) {
            for (Long id : new Long[] { s.getRecipeId(), s.getSideRecipeId() }) {
                Recipe r = id == null ? null : byId.get(id);
                if (r == null) continue;
                Map<DozenItem, Integer> day = byDay.computeIfAbsent(s.getDate(), d -> new HashMap<>());
                for (RecipeTick t : r.getTicks()) day.merge(t.getItem(), t.getCount(), Integer::sum);
            }
        }

        List<DozenGapView.ItemGap> items = new ArrayList<>();
        for (DozenItem item : DozenItem.values()) {
            if (!item.isFromRecipes()) continue;

            int daysMet = 0;
            for (int i = 0; i < 7; i++) {
                int count = byDay.getOrDefault(start.plusDays(i), Map.of()).getOrDefault(item, 0);
                if (count >= item.getDailyTarget()) daysMet++;
            }
            List<Recipe> covering = library.stream().filter(r -> r.tickCount(item) > 0).toList();
            List<Recipe> reachable = covering.stream().filter(r -> fitsAnOpenSlot(r, openMeals)).toList();

            // Meals the stranded recipes are waiting on.
            List<String> blocked = covering.stream()
                    .filter(r -> !fitsAnOpenSlot(r, openMeals))
                    .flatMap(r -> r.getYields().stream())
                    .filter(RecipeYield::isUsable)
                    .flatMap(y -> y.getMealTypes().stream())
                    .filter(m -> !openMeals.contains(m))
                    .distinct()
                    .map(MealType::getLabel)
                    .toList();

            items.add(new DozenGapView.ItemGap(
                    item.name(), item.getLabel(), item.getDailyTarget(), daysMet,
                    covering.size(), reachable.size(), blocked,
                    verdict(daysMet, covering.size(), reachable.size())));
        }

        // Worst first: fewest days met, then fewest recipes that can actually be used.
        items.sort(Comparator.comparingInt(DozenGapView.ItemGap::daysMet)
                .thenComparingInt(DozenGapView.ItemGap::recipesReachable)
                .thenComparingInt(DozenGapView.ItemGap::recipesCovering));

        return new DozenGapView(start, items, advice(items, library.size()));
    }

    private String verdict(int daysMet, int covering, int reachable) {
        // How the week actually went comes first — the recipe count is shown alongside anyway,
        // so "only one recipe" must not mask "never once hit the target".
        if (covering == 0) return "No recipe ticks this at all";
        // Recipes that only fit switched-off slots can never be scheduled, so say so before
        // reporting the week: the count alone reads as coverage the planner doesn't have.
        if (reachable == 0) return "Recipes exist, but only for meals you've turned off";
        if (daysMet == 7) return "Hit every day";
        if (daysMet == 0) return "Never reached the daily target";
        if (covering == 1) return "Only one recipe covers this";
        return "Short on " + (7 - daysMet) + " day" + (7 - daysMet > 1 ? "s" : "");
    }

    /** Sides count as reachable too: they're added by hand, but they do reach the plan. */
    private boolean fitsAnOpenSlot(Recipe r, Set<MealType> openMeals) {
        return r.getYields().stream()
                .filter(RecipeYield::isUsable)
                .anyMatch(y -> y.getMealTypes().stream().anyMatch(openMeals::contains));
    }

    /** Plain-language pointers at what to add next, worst problems first. */
    private List<String> advice(List<DozenGapView.ItemGap> items, int librarySize) {
        List<String> out = new ArrayList<>();
        if (librarySize == 0) {
            out.add("No recipes yet — add a few and this will show which Daily Dozen items your library can't reach.");
            return out;
        }

        List<String> missing = items.stream()
                .filter(i -> i.recipesCovering() == 0).map(DozenGapView.ItemGap::label).toList();

        if (!missing.isEmpty()) {
            out.add("Nothing in your library ticks " + join(missing) +
                    ". A recipe with " + (missing.size() == 1 ? "it" : "them") + " would lift every week.");
        }

        // The trap that prompted this: recipes that tick an item but only fit switched-off slots.
        for (DozenGapView.ItemGap i : items) {
            if (i.recipesCovering() > 0 && i.recipesReachable() == 0 && !i.blockedMeals().isEmpty()) {
                out.add(i.recipesCovering() + " recipe" + (i.recipesCovering() > 1 ? "s" : "") +
                        " tick " + i.label() + ", but only fit your " + join(i.blockedMeals()).toLowerCase() +
                        " slots — switch one on for a day to put " +
                        (i.recipesCovering() > 1 ? "them" : "it") + " in play.");
            }
        }

        List<String> thin = items.stream()
                .filter(i -> i.recipesCovering() == 1 && i.recipesReachable() > 0)
                .map(DozenGapView.ItemGap::label).toList();
        if (!thin.isEmpty()) {
            out.add("Only one recipe covers " + join(thin) +
                    " — a second would give the planner room to vary things.");
        }

        List<String> shortfall = items.stream()
                .filter(i -> i.recipesReachable() > 1 && i.daysMet() < 4)
                .map(i -> i.label().toLowerCase() + " (" + i.daysMet() + "/7 days)")
                .limit(3).toList();
        if (!shortfall.isEmpty()) {
            out.add("You have recipes for these but rarely hit the target: " + join(shortfall) +
                    ". Recipes with a bigger serving of them would help.");
        }

        if (out.isEmpty()) out.add("Your library covers all ten items and the week is hitting them well.");
        return out;
    }

    private String join(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
