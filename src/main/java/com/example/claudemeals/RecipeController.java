package com.example.claudemeals;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/recipes")
public class RecipeController {

    private final RecipeRepository recipes;
    private final MealSlotRepository slots;

    public RecipeController(RecipeRepository recipes, MealSlotRepository slots) {
        this.recipes = recipes;
        this.slots = slots;
    }

    @GetMapping
    public List<Recipe> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return includeInactive ? recipes.findAll() : recipes.findByActiveTrueOrderByNameAsc();
    }

    @PostMapping
    public Recipe create(@RequestBody Recipe recipe) {
        recipe.setId(null);
        recipe.setVerified(true);        // Ian typed it in, so there's nothing to check
        return recipes.save(clean(recipe));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Recipe> update(@PathVariable Long id, @RequestBody Recipe incoming) {
        Recipe existing = recipes.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        existing.setName(incoming.getName());
        existing.setNotes(incoming.getNotes());
        existing.setActive(incoming.isActive());
        existing.setSide(incoming.isSide());
        existing.setVerified(true);      // editing it is verifying it
        // rating and snooze are owned by their own flows, so a recipe edit leaves them alone
        clean(incoming);
        existing.getYields().clear();
        existing.getYields().addAll(incoming.getYields());
        existing.getTicks().clear();
        existing.getTicks().addAll(incoming.getTicks());
        return ResponseEntity.ok(recipes.save(existing));
    }

    /**
     * Holds a recipe back from this week and the next, and pulls it out of any plan it's already
     * in for those weeks. Hand-pinned placements are left alone — an explicit choice outranks
     * a snooze.
     */
    @PostMapping("/{id}/snooze")
    @Transactional
    public ResponseEntity<Recipe> snooze(@PathVariable Long id,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                         LocalDate fromWeek) {
        Recipe recipe = recipes.findById(id).orElse(null);
        if (recipe == null) return ResponseEntity.notFound().build();

        LocalDate start = fromWeek;
        LocalDate through = start.plusWeeks(1);
        recipe.setSnoozedThroughWeek(through);

        List<MealSlot> affected = slots.findByDateBetweenOrderByDateAsc(start, through.plusDays(6));
        for (MealSlot s : affected) {
            if (id.equals(s.getRecipeId()) && !s.isPinned()) s.clearAssignment();
        }
        slots.saveAll(affected);
        return ResponseEntity.ok(recipes.save(recipe));
    }

    @DeleteMapping("/{id}/snooze")
    public ResponseEntity<Recipe> unsnooze(@PathVariable Long id) {
        Recipe recipe = recipes.findById(id).orElse(null);
        if (recipe == null) return ResponseEntity.notFound().build();
        recipe.setSnoozedThroughWeek(null);
        return ResponseEntity.ok(recipes.save(recipe));
    }

    /**
     * Deletes a recipe and everything pointing at it — any meal or side it was assigned to. Without clearing the slots, a deleted recipe leaves a slot that still
     * looks filled but has no recipe behind it, which no replan would ever fix.
     */
    /** One verdict per recipe, set straight from a meal. Pass no rating to clear it. */
    @PutMapping("/{id}/rating")
    public ResponseEntity<Recipe> rate(@PathVariable Long id,
                                       @RequestParam(required = false) RecipeRating rating) {
        Recipe recipe = recipes.findById(id).orElse(null);
        if (recipe == null) return ResponseEntity.notFound().build();
        recipe.setRating(rating);
        return ResponseEntity.ok(recipes.save(recipe));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!recipes.existsById(id)) return ResponseEntity.notFound().build();

        List<MealSlot> referencing = slots.findByRecipeIdOrSideRecipeId(id, id);
        for (MealSlot s : referencing) {
            if (id.equals(s.getRecipeId())) s.clearAssignment();
            if (id.equals(s.getSideRecipeId())) s.setSideRecipeId(null);
        }
        slots.saveAll(referencing);

        recipes.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Drops zero/blank rows so the planner never sees a yield or tick that means nothing. */
    private Recipe clean(Recipe r) {
        r.getYields().removeIf(y -> !y.isUsable());
        r.getTicks().removeIf(t -> t.getItem() == null || t.getCount() <= 0);
        return r;
    }
}
