package com.example.claudemeals;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * One meal on one day. Slots are created lazily when a week is first viewed.
 * {@code home} = we're responsible for planning it; toggling it off is the "eating out" mark.
 */
@Entity
@Table(name = "meal_slots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"slot_date", "meal_type"}))
public class MealSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_date")
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type")
    private MealType mealType;

    private boolean home;

    @Column(name = "recipe_id")
    private Long recipeId;

    /** The day the assigned recipe was cooked; differs from {@code date} when this is a leftover. */
    @Column(name = "cook_date")
    private LocalDate cookDate;

    /** An optional side dish alongside the main. */
    @Column(name = "side_recipe_id")
    private Long sideRecipeId;

    /**
     * The day the side was actually made. Without this every appearance of a six-serving pot of
     * beans reads as a separate cooking session, which is what made prep lists look impossible.
     */
    @Column(name = "side_cook_date")
    private LocalDate sideCookDate;

    /** True when the user placed the recipe by hand, so the planner leaves it alone. */
    private boolean pinned;

    /**
     * Only meaningful for the optional smoothie/snack slots. Off-by-default and
     * deliberately-switched-off look identical otherwise, and the planner needs to tell them
     * apart: it may open the former to close a Daily Dozen gap, but must never reopen the latter.
     */
    @Column(name = "opt_out")
    private boolean optOut;

    /** True when the user chose the side themselves, so a replan leaves it in place. */
    @Column(name = "side_pinned")
    private boolean sidePinned;

    protected MealSlot() {}

    public MealSlot(LocalDate date, MealType mealType) {
        this.date = date;
        this.mealType = mealType;
        this.home = mealType.isRequired();
    }

    public Long getId() { return id; }
    public LocalDate getDate() { return date; }
    public MealType getMealType() { return mealType; }
    public boolean isHome() { return home; }
    public void setHome(boolean home) { this.home = home; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public LocalDate getCookDate() { return cookDate; }
    public void setCookDate(LocalDate cookDate) { this.cookDate = cookDate; }
    public Long getSideRecipeId() { return sideRecipeId; }
    public void setSideRecipeId(Long sideRecipeId) { this.sideRecipeId = sideRecipeId; }
    public boolean isSidePinned() { return sidePinned; }
    public void setSidePinned(boolean sidePinned) { this.sidePinned = sidePinned; }
    public LocalDate getSideCookDate() { return sideCookDate; }
    public void setSideCookDate(LocalDate sideCookDate) { this.sideCookDate = sideCookDate; }

    /** True when the side on this meal came out of a batch made on an earlier day. */
    public boolean isSideLeftover() {
        return sideCookDate != null && !sideCookDate.equals(date);
    }

    public void clearSide() {
        this.sideRecipeId = null;
        this.sidePinned = false;
        this.sideCookDate = null;
    }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public boolean isOptOut() { return optOut; }
    public void setOptOut(boolean optOut) { this.optOut = optOut; }

    /** An optional slot the planner is allowed to open if the day needs the Daily Dozen help. */
    public boolean isOpenableByPlanner() {
        return !mealType.isRequired() && !home && !optOut;
    }

    public boolean isFilled() { return recipeId != null; }

    /** Leftovers are servings eaten on a day after the cook day. */
    public boolean isLeftover() { return cookDate != null && !cookDate.equals(date); }

    /**
     * Clears the main course only. Sides are the user's own additions, so they survive a replan;
     * use {@link #clearAll()} when the whole slot is being emptied.
     */
    public void clearAssignment() {
        this.recipeId = null;
        this.cookDate = null;
        this.pinned = false;
    }

    /** Empties the slot completely, side included. */
    public void clearAll() {
        clearAssignment();
        clearSide();
    }
}
