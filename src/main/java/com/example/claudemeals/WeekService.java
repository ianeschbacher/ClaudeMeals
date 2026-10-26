package com.example.claudemeals;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class WeekService {

    private final MealSlotRepository slots;
    private final RecipeRepository recipes;
    private final PlanService planner;
    public WeekService(MealSlotRepository slots, RecipeRepository recipes, PlanService planner) {
        this.slots = slots;
        this.recipes = recipes;
        this.planner = planner;
    }

    /*
     * A planning period is seven days anchored wherever planning started — the day Ian shops.
     * There is no weekday alignment, so any date is its own period start and needs no
     * normalising; use currentPeriodStart() for the period that is actually running.
     */

    /**
     * The period covering today, or today itself when none does. A period that has run out
     * simply stops being current, which is the cue to plan the next seven days.
     */
    /** Planning always looks forward from today — there is no stored window to go stale. */
    public LocalDate currentPeriodStart() {
        return LocalDate.now();
    }

    /** Loads the week's slots, creating any that don't exist yet. */
    @Transactional
    public List<MealSlot> slotsFor(LocalDate start) {
        LocalDate end = start.plusDays(6);
        List<MealSlot> existing = slots.findByDateBetweenOrderByDateAsc(start, end);

        Set<String> have = new HashSet<>();
        for (MealSlot s : existing) have.add(s.getDate() + "|" + s.getMealType());

        List<MealSlot> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = start.plusDays(i);
            for (MealType type : MealType.values()) {
                if (!have.contains(day + "|" + type)) created.add(new MealSlot(day, type));
            }
        }
        if (!created.isEmpty()) {
            List<MealSlot> all = new ArrayList<>(existing);
            all.addAll(created);
            existing = new ArrayList<>(slots.saveAll(all));
        }

        existing.sort(Comparator.comparing(MealSlot::getDate).thenComparing(s -> s.getMealType().ordinal()));
        return existing;
    }

    /** Clears unpinned assignments, then runs the planner over the week. */
    @Transactional
    public void autofill(LocalDate start) {
        List<MealSlot> week = slotsFor(start);
        for (MealSlot s : week) {
            if (!s.isPinned()) s.clearAssignment();
            if (!s.isSidePinned()) s.clearSide();
        }
        // Snoozed recipes sit this week out entirely.
        List<Recipe> available = recipes.findByActiveTrueOrderByNameAsc().stream()
                .filter(r -> !r.isSnoozedFor(start))
                .toList();
        planner.fill(week, available, previousWeek(start));
        slots.saveAll(week);
    }

    /**
     * Last week's meals, read-only context for planning: they tell the planner what was eaten
     * recently and which batches still have servings left to spill into the new week.
     */
    private List<MealSlot> previousWeek(LocalDate start) {
        LocalDate prev = start.minusWeeks(1);
        return slots.findByDateBetweenOrderByDateAsc(prev, prev.plusDays(6));
    }

    /**
     * Wipes every assignment in the week, pinned included. Optional slots go back to "auto"
     * so the planner decides them afresh; an explicit opt-out is left alone.
     */
    @Transactional
    public void clear(LocalDate start) {
        List<MealSlot> week = slotsFor(start);
        for (MealSlot s : week) {
            s.clearAll();
            if (!s.getMealType().isRequired() && !s.isOptOut()) s.setHome(false);
        }
        slots.saveAll(week);
    }
}
