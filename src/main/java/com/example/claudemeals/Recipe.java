package com.example.claudemeals;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "recipes")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Free-form: source, notes, whatever. Method/instructions are deliberately not tracked. */
    @Column(length = 2000)
    private String notes;

    private boolean active = true;

    /**
     * A side is never scheduled on its own — the planner fills each meal with one main course,
     * and sides are added onto a meal by hand. Their Daily Dozen ticks still count.
     */
    private boolean side;

    /** Where this recipe came from if it wasn't hand-entered, e.g. "Paprika 2026-08-16". */
    @Column(name = "imported_from")
    private String importedFrom;

    /**
     * True once Ian has looked at the recipe's meals and Daily Dozen ticks and accepted them.
     * Hand-entered recipes start verified; imported guesses don't, which is what the review
     * page lists.
     */
    private boolean verified;

    /**
     * Standing verdict, set by reviewing a week the recipe was scheduled in. Null means untried.
     * Always the most recent non-SKIPPED review — see {@link ReviewService}.
     */
    @Enumerated(EnumType.STRING)
    private RecipeRating rating;

    /**
     * Week start (inclusive) through which this recipe is held back from planning.
     * Null means available. Set by snoozing from the weekly plan.
     */
    @Column(name = "snoozed_through_week")
    private LocalDate snoozedThroughWeek;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recipe_yields", joinColumns = @JoinColumn(name = "recipe_id"))
    private List<RecipeYield> yields = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recipe_ticks", joinColumns = @JoinColumn(name = "recipe_id"))
    private List<RecipeTick> ticks = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isSide() { return side; }
    public void setSide(boolean side) { this.side = side; }
    public String getImportedFrom() { return importedFrom; }
    public void setImportedFrom(String importedFrom) { this.importedFrom = importedFrom; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public LocalDate getSnoozedThroughWeek() { return snoozedThroughWeek; }
    public void setSnoozedThroughWeek(LocalDate week) { this.snoozedThroughWeek = week; }

    /** True if the recipe is held back from the period starting {@code weekStart}. */
    public boolean isSnoozedFor(LocalDate weekStart) {
        return snoozedThroughWeek != null && !snoozedThroughWeek.isBefore(weekStart);
    }

    /**
     * The most meals this recipe may fill in one week. The servings on the card are a ceiling,
     * not a quota: the planner fills up to this many and no more, but is never obliged to use
     * them all. Without this the planner would simply cook a second batch.
     */
    public int weeklyServings() {
        return yields.stream().filter(RecipeYield::isUsable).mapToInt(RecipeYield::getServings).sum();
    }

    public RecipeRating getRating() { return rating; }
    public void setRating(RecipeRating rating) { this.rating = rating; }
    public List<RecipeYield> getYields() { return yields; }
    public void setYields(List<RecipeYield> yields) { this.yields = yields; }
    public List<RecipeTick> getTicks() { return ticks; }
    public void setTicks(List<RecipeTick> ticks) { this.ticks = ticks; }

    /** True if any yield can be spent on this meal type. */
    public boolean canCover(MealType type) {
        return yields.stream().anyMatch(y -> y.isUsable() && y.covers(type));
    }

    /** Daily Dozen count this recipe contributes per serving for the given item. */
    public int tickCount(DozenItem item) {
        return ticks.stream()
                .filter(t -> t.getItem() == item)
                .mapToInt(RecipeTick::getCount)
                .sum();
    }
}
