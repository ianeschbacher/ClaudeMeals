package com.example.claudemeals;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/week")
public class WeekController {

    private final WeekService weeks;
    private final MealSlotRepository slots;
    private final RecipeRepository recipes;
    private final PlanService planner;

    public WeekController(WeekService weeks, MealSlotRepository slots, RecipeRepository recipes,
                          PlanService planner) {
        this.weeks = weeks;
        this.slots = slots;
        this.recipes = recipes;
        this.planner = planner;
    }

    @GetMapping
    public WeekView week(@RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        LocalDate weekStart = start == null ? weeks.currentPeriodStart() : start;
        return view(weekStart, weeks.slotsFor(weekStart));
    }

    /** Runs the planner and returns the resulting week, gaps included. */
    @PostMapping("/autofill")
    public WeekView autofill(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        LocalDate weekStart = start;
        weeks.autofill(weekStart);
        return view(weekStart, weeks.slotsFor(weekStart));
    }

    @PostMapping("/clear")
    public WeekView clear(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        LocalDate weekStart = start;
        weeks.clear(weekStart);
        return view(weekStart, weeks.slotsFor(weekStart));
    }

    /** Toggle whether we're eating this meal at home. Turning it off drops any assignment. */
    @PutMapping("/slots/{id}/home")
    public ResponseEntity<Void> setHome(@PathVariable Long id, @RequestParam boolean home) {
        MealSlot slot = slots.findById(id).orElse(null);
        if (slot == null) return ResponseEntity.notFound().build();
        slot.setHome(home);
        // Switching an optional slot off is a decision, not a default — remember it so the
        // planner doesn't quietly put the snack back next time it wants a few more greens.
        if (!slot.getMealType().isRequired()) slot.setOptOut(!home);
        if (!home) slot.clearAll();
        slots.save(slot);
        return ResponseEntity.noContent().build();
    }

    /**
     * "Didn't make it": drops the whole batch — this meal and every later one that was eating
     * its leftovers. One tap, on the day you'd notice, instead of reconstructing a week later.
     */
    @PutMapping("/slots/{id}/not-made")
    @Transactional
    public ResponseEntity<Void> notMade(@PathVariable Long id) {
        MealSlot slot = slots.findById(id).orElse(null);
        if (slot == null) return ResponseEntity.notFound().build();
        if (slot.getRecipeId() == null) return ResponseEntity.badRequest().build();

        Long recipeId = slot.getRecipeId();
        LocalDate cooked = slot.getCookDate() == null ? slot.getDate() : slot.getCookDate();
        List<MealSlot> batch = slots.findByRecipeIdOrSideRecipeId(recipeId, recipeId).stream()
                .filter(s -> recipeId.equals(s.getRecipeId()))
                .filter(s -> cooked.equals(s.getCookDate()))
                .toList();
        batch.forEach(MealSlot::clearAssignment);
        slots.saveAll(batch);
        return ResponseEntity.noContent().build();
    }

    /**
     * Adds or clears the side dish on a meal. Sides are hand-picked, so no rating tier or
     * serving ceiling applies — and the slot must already have a main to hang it off.
     */
    @PutMapping("/slots/{id}/side")
    public ResponseEntity<Void> setSide(@PathVariable Long id,
                                       @RequestParam(required = false) Long recipeId) {
        MealSlot slot = slots.findById(id).orElse(null);
        if (slot == null) return ResponseEntity.notFound().build();
        if (recipeId == null) {
            slot.clearSide();
        } else {
            Recipe side = recipes.findById(recipeId).orElse(null);
            if (side == null || !side.isSide()) return ResponseEntity.badRequest().build();
            if (!slot.isFilled()) return ResponseEntity.badRequest().build();
            slot.setSideRecipeId(recipeId);
            slot.setSideCookDate(slot.getDate());   // made that day, its own little batch
            slot.setSidePinned(true);      // the user's own choice outranks the planner's
        }
        slots.save(slot);
        return ResponseEntity.noContent().build();
    }

    /**
     * Place a recipe by hand, or pass no recipeId to clear the slot. Extra servings spread
     * forward into later empty meals exactly as they would if the planner had chosen it —
     * picking a two-breakfast recipe should fill two breakfasts, not one.
     */
    @PutMapping("/slots/{id}/recipe")
    @Transactional
    public ResponseEntity<Void> setRecipe(@PathVariable Long id,
                                          @RequestParam(required = false) Long recipeId) {
        MealSlot known = slots.findById(id).orElse(null);
        if (known == null) return ResponseEntity.notFound().build();

        List<MealSlot> week = weeks.slotsFor(known.getDate());
        MealSlot target = week.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(known);

        // Whatever was here before takes its own leftovers with it.
        clearLeftoversOf(target, week);
        target.clearAssignment();

        if (recipeId != null) {
            Recipe recipe = recipes.findById(recipeId).orElse(null);
            if (recipe == null || recipe.isSide()) return ResponseEntity.badRequest().build();
            target.setHome(true);
            target.setOptOut(false);
            for (MealSlot s : planner.spreadByHand(recipe, target, week)) {
                s.setRecipeId(recipe.getId());
                s.setCookDate(target.getDate());
                s.setPinned(true);
            }
        }

        slots.saveAll(week);
        return ResponseEntity.noContent().build();
    }

    /**
     * Clears the later meals that were leftovers of the batch cooked in {@code cook}, so
     * changing a meal doesn't strand portions of the dish it replaced further down the week.
     */
    private void clearLeftoversOf(MealSlot cook, List<MealSlot> week) {
        if (cook.getRecipeId() == null) return;
        for (MealSlot s : week) {
            if (s.equals(cook) || s.getRecipeId() == null) continue;
            if (!cook.getRecipeId().equals(s.getRecipeId())) continue;
            if (cook.getDate().equals(s.getCookDate()) && s.getDate().isAfter(cook.getDate())) {
                s.clearAssignment();
            }
        }
    }

    /**
     * Moves a planned meal into another slot, swapping if the target already has one.
     * Both ends get pinned — a hand-placed meal shouldn't be undone by the next replan.
     */
    @PutMapping("/slots/{id}/move")
    public ResponseEntity<Void> move(@PathVariable Long id, @RequestParam Long toSlotId) {
        MealSlot from = slots.findById(id).orElse(null);
        MealSlot to = slots.findById(toSlotId).orElse(null);
        if (from == null || to == null) return ResponseEntity.notFound().build();
        if (from.getId().equals(to.getId())) return ResponseEntity.noContent().build();
        if (from.getRecipeId() == null) return ResponseEntity.badRequest().build();

        Long movingRecipe = from.getRecipeId();
        LocalDate movingCook = from.getCookDate();
        Long displacedRecipe = to.getRecipeId();
        LocalDate displacedCook = to.getCookDate();

        to.setRecipeId(movingRecipe);
        to.setCookDate(cookDateFor(movingCook, to.getDate()));
        to.setPinned(true);
        to.setHome(true);
        to.setOptOut(false);

        if (displacedRecipe != null) {
            from.setRecipeId(displacedRecipe);
            from.setCookDate(cookDateFor(displacedCook, from.getDate()));
            from.setPinned(true);
        } else {
            from.clearAssignment();
        }

        slots.saveAll(List.of(from, to));
        return ResponseEntity.noContent().build();
    }

    /**
     * Keeps the leftover relationship when the meal still lands on or after its cook day;
     * moving it earlier means it's now being cooked that day instead.
     */
    private static LocalDate cookDateFor(LocalDate cookDate, LocalDate newDate) {
        return cookDate != null && !newDate.isBefore(cookDate) ? cookDate : newDate;
    }

    private WeekView view(LocalDate start, List<MealSlot> weekSlots) {
        Map<Long, Recipe> byId = new HashMap<>();
        for (Recipe r : recipes.findAll()) byId.put(r.getId(), r);

        List<WeekView.Day> days = new ArrayList<>();
        Map<String, Integer> gapsByMeal = new LinkedHashMap<>();
        int gapCount = 0;

        for (int i = 0; i < 7; i++) {
            LocalDate date = start.plusDays(i);
            List<MealSlot> daySlots = weekSlots.stream().filter(s -> s.getDate().equals(date)).toList();

            Map<DozenItem, Integer> counts = new EnumMap<>(DozenItem.class);
            List<WeekView.Slot> slotViews = new ArrayList<>();
            for (MealSlot s : daySlots) {
                Recipe r = s.getRecipeId() == null ? null : byId.get(s.getRecipeId());
                Recipe side = s.getSideRecipeId() == null ? null : byId.get(s.getSideRecipeId());
                if (r != null) {
                    for (RecipeTick t : r.getTicks()) counts.merge(t.getItem(), t.getCount(), Integer::sum);
                }
                if (side != null) {
                    for (RecipeTick t : side.getTicks()) counts.merge(t.getItem(), t.getCount(), Integer::sum);
                }
                if (s.isHome() && s.getMealType().isRequired() && !s.isFilled()) {
                    gapsByMeal.merge(s.getMealType().getLabel(), 1, Integer::sum);
                    gapCount++;
                }
                slotViews.add(new WeekView.Slot(
                        s.getId(), s.getMealType().name(), s.getMealType().getLabel(),
                        s.getMealType().isRequired(), s.isHome(), s.isOptOut(),
                        s.getRecipeId(), r == null ? null : r.getName(),
                        s.getSideRecipeId(), side == null ? null : side.getName(),
                        s.isSideLeftover(), s.isLeftover(), s.isPinned()));
            }

            List<WeekView.DozenProgress> dozen = new ArrayList<>();
            int hit = 0, possible = 0;
            for (DozenItem item : DozenItem.values()) {
                int count = counts.getOrDefault(item, 0);
                boolean met = count >= item.getDailyTarget();
                if (item.isFromRecipes()) {
                    possible++;
                    if (met) hit++;
                }
                dozen.add(new WeekView.DozenProgress(
                        item.name(), item.getLabel(), count, item.getDailyTarget(), met));
            }

            days.add(new WeekView.Day(date, Words.dayName(date), slotViews, dozen, hit, possible));
        }

        List<String> byMeal = gapsByMeal.entrySet().stream()
                .map(e -> e.getValue() + " " + Words.plural(e.getKey().toLowerCase(), e.getValue()))
                .toList();
        WeekView.Gaps gaps = new WeekView.Gaps(gapCount, byMeal);

        return new WeekView(start, days, gaps);
    }

}
