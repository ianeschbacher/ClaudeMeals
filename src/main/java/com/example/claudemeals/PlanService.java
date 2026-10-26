package com.example.claudemeals;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Fills the open home slots in a week.
 *
 * Greedy, earliest-slot-first: repeatedly take the earliest unfilled slot and try every recipe
 * that could be cooked that day to cover it. A cook covers its own slot plus any further slots
 * its extra servings reach (leftovers roll forward, never backward). A yield listing several meal
 * types shares one pool of servings across them, so "breakfast or snack" is spent once, not twice. The winning cook is the one
 * that adds the most Daily Dozen servings still missing on the days it feeds, lightly penalised
 * for repeating a recipe already used that week.
 *
 * Required meals (breakfast/lunch/dinner) are filled first. The optional smoothie/snack slots
 * are then opened automatically, but kept only where they close a Daily Dozen gap the day's
 * main meals left open — a slot the user has explicitly switched off is never reopened.
 * Sides are never scheduled. A day that still comes up short just reports the gap — a handful
 * of fruit closes it more cheaply than another dish to cook.
 *
 * Ratings gate the candidates before score is even considered: the best available tier wins
 * (thumbs up, then untried, then thumbs middle). Two things open it up — a tier whose recipes
 * have all been cooked already this week is "exhausted" and lets the next tier in, and a
 * lower-tier recipe covering MORE required meals than anything eligible is filling a gap.
 * Thumbs down sits out of both and only surfaces when nothing else fits at all.
 */
@Service
public class PlanService {

    /** Dozen servings beyond a day's target still count for a little, so surplus isn't wasted. */
    private static final double SURPLUS_WEIGHT = 0.15;
    /**
     * Variety: each prior cook of the same recipe in the week scales its score down by this much.
     * Proportional rather than subtractive, so a high-scoring recipe can't repeat all week just
     * because its raw score dwarfs a flat penalty.
     */
    private static final double REPEAT_DECAY = 0.55;
    /**
     * How much a recipe cooked last week is played down this week. A nudge, not a ban — with a
     * thin choice for some meal it can still win. Carried-over leftovers are placed before any
     * of this runs, so a batch spanning the weekend is never penalised for being the same dish.
     */
    private static final double LAST_WEEK_DECAY = 0.6;
    /**
     * How far back a batch can have been cooked and still spill into the new week. Four days
     * reaches back to Thursday, so a big Thursday batch feeds into Monday and Tuesday.
     */
    private static final int CARRY_BACK_DAYS = 4;
    /**
     * How deep into the new week last week's leftovers may reach. Three days keeps a weekend
     * batch feeding Mon-Wed; beyond that it's a repeat pretending to be leftovers.
     */
    private static final int CARRY_FORWARD_DAYS = 3;
    /**
     * How many dishes may be started from scratch on one day. Leftovers are free, so this is
     * the real measure of a day's work.
     */
    private static final int BATCHES_PER_DAY = 3;

    public void fill(List<MealSlot> weekSlots, List<Recipe> recipes) {
        fill(weekSlots, recipes, List.of());
    }

    public void fill(List<MealSlot> weekSlots, List<Recipe> recipes, List<MealSlot> lastWeek) {
        List<MealSlot> slots = new ArrayList<>(weekSlots);
        slots.sort(Comparator.comparing(MealSlot::getDate).thenComparing(s -> s.getMealType().ordinal()));

        Map<LocalDate, Map<DozenItem, Integer>> dozenByDay = new HashMap<>();
        Map<Long, Integer> useCount = new HashMap<>();
        // Meals each recipe has already claimed this week, capped by its total servings.
        Map<Long, Integer> slotsUsed = new HashMap<>();
        Map<Long, Recipe> byId = new HashMap<>();
        for (Recipe r : recipes) byId.put(r.getId(), r);

        // Only main courses compete for a meal — a slot never gets two mains. Sides reach the
        // plan by hand, but their ticks still count toward the day (see the seeding below).
        List<Recipe> mains = recipes.stream().filter(r -> !r.isSide()).toList();

        // Seed the running totals with whatever is already assigned (pinned or previously planned).
        for (MealSlot s : slots) {
            // Sides count toward the day whether they were chosen by hand or by the planner.
            Recipe side = s.getSideRecipeId() == null ? null : byId.get(s.getSideRecipeId());
            if (side != null) addTicks(dozenByDay, s.getDate(), side);

            if (!s.isFilled()) continue;
            Recipe r = byId.get(s.getRecipeId());
            if (r == null) continue;
            addTicks(dozenByDay, s.getDate(), r);
            slotsUsed.merge(r.getId(), 1, Integer::sum);
            if (s.getCookDate() == null || s.getCookDate().equals(s.getDate())) {
                useCount.merge(r.getId(), 1, Integer::sum);
            }
        }

        // A batch cooked late last week finishes itself off here, before anything else is
        // chosen — this is the same pot of food, not a repeat, so nothing penalises it.
        carryOverLeftovers(slots, lastWeek, byId, dozenByDay, slotsUsed);

        // What last week actually ate, so this week can lean elsewhere.
        Set<Long> lastWeekRecipes = new HashSet<>();
        for (MealSlot s : lastWeek) {
            if (s.getRecipeId() != null) lastWeekRecipes.add(s.getRecipeId());
        }

        // Slots nothing could cover, kept so the loop below doesn't retry them forever.
        // Callers read the actual shortfall off the slots themselves.
        List<MealSlot> skipped = new ArrayList<>();

        // Pass 1: the meals the week must account for. Pass 2: the optional extras.
        for (boolean requiredPass : new boolean[] { true, false }) {
            // A smoothie or snack is there to rescue a day the main meals left short, so open
            // every one the user hasn't explicitly switched off and let the gain test below
            // decide which actually earn a place.
            List<MealSlot> provisional = new ArrayList<>();
            if (!requiredPass) {
                for (MealSlot s : slots) {
                    if (s.isOpenableByPlanner()) {
                        s.setHome(true);
                        provisional.add(s);
                    }
                }
            }

            while (true) {
                MealSlot target = slots.stream()
                        .filter(s -> s.isHome() && !s.isFilled())
                        .filter(s -> s.getMealType().isRequired() == requiredPass)
                        .filter(s -> !skipped.contains(s))
                        .findFirst().orElse(null);
                if (target == null) break;

                // An optional slot only earns a place on a day that is still short.
                if (!requiredPass && !dayShort(dozenByDay, target.getDate())) {
                    skipped.add(target);
                    continue;
                }

                // Every cook starts a batch, so a day at its limit can't host a new one.
                if (!canCookOn(target.getDate(), slots)) {
                    skipped.add(target);
                    continue;
                }

                List<Cook> candidates = new ArrayList<>();
                for (Recipe r : mains) {
                    if (!r.canCover(target.getMealType())) continue;
                    Cook cook = simulate(r, target, slots, dozenByDay, useCount, slotsUsed,
                            requiredPass, false, lastWeekRecipes, target.getDate());
                    if (cook != null) candidates.add(cook);
                }
                Cook best = choose(candidates);

                // An optional slot only earns its place if it moves the day toward a Daily Dozen
                // target it hasn't met — otherwise it's a snack for the sake of a snack.
                if (best == null || (!requiredPass && best.towardUnmet() <= 0)) {
                    skipped.add(target);
                    continue;
                }

                for (MealSlot s : best.covered()) {
                    s.setRecipeId(best.recipe().getId());
                    s.setCookDate(best.cookDate());
                    addTicks(dozenByDay, s.getDate(), best.recipe());
                }
                useCount.merge(best.recipe().getId(), 1, Integer::sum);
                slotsUsed.merge(best.recipe().getId(), best.covered().size(), Integer::sum);
            }

            // Now that every day has cooked for what it was missing, the spare servings of
            // those batches fill whatever is still empty.
            spreadSpareServings(slots, byId, slotsUsed);

            // Anything we opened speculatively but didn't use goes back to being off, so the
            // week doesn't sprout empty snack slots.
            for (MealSlot s : provisional) {
                if (!s.isFilled()) s.setHome(false);
            }
        }

    }

    /**
     * Finishes off batches cooked late last week. A cook produces its whole yield at once, so
     * if last week only got through part of it the rest is real food sitting in the fridge —
     * it fills the start of this week as leftovers of that original cook, keeping its cook date.
     */
    private void carryOverLeftovers(List<MealSlot> slots, List<MealSlot> lastWeek,
                                    Map<Long, Recipe> byId,
                                    Map<LocalDate, Map<DozenItem, Integer>> dozenByDay,
                                    Map<Long, Integer> slotsUsed) {
        if (lastWeek.isEmpty() || slots.isEmpty()) return;
        LocalDate weekStart = slots.get(0).getDate();

        // Group last week's meals into the batches they came from — sides included, since a
        // batched side has leftovers exactly like a main does.
        Map<String, List<MealSlot>> batches = new LinkedHashMap<>();
        for (MealSlot s : lastWeek) {
            if (s.getRecipeId() != null) {
                LocalDate cooked = s.getCookDate() == null ? s.getDate() : s.getCookDate();
                batches.computeIfAbsent(s.getRecipeId() + "@" + cooked, k -> new ArrayList<>()).add(s);
            }
            if (s.getSideRecipeId() != null) {
                LocalDate cooked = s.getSideCookDate() == null ? s.getDate() : s.getSideCookDate();
                batches.computeIfAbsent(s.getSideRecipeId() + "@" + cooked, k -> new ArrayList<>()).add(s);
            }
        }

        for (Map.Entry<String, List<MealSlot>> e : batches.entrySet()) {
            List<MealSlot> batch = e.getValue();
            Long recipeId = Long.valueOf(e.getKey().substring(0, e.getKey().indexOf('@')));
            Recipe recipe = byId.get(recipeId);
            if (recipe == null) continue;
            if (recipe.isSide()) continue;                // sides are never planned

            LocalDate cooked = LocalDate.parse(e.getKey().substring(e.getKey().indexOf('@') + 1));

            // Assume a planned batch happened. That's only safe because putting it right is a
            // single tap on Today ("didn't make it"), rather than a page of questions later.
            if (cooked.isBefore(weekStart.minusDays(CARRY_BACK_DAYS))) continue;   // long gone
            int remaining = recipe.weeklyServings() - batch.size();

            // Leftovers can go to any meal the recipe suits, earliest first — but only into
            // the front of the week, so a weekend batch doesn't turn up again on Friday.
            LocalDate lastUseful = weekStart.plusDays(CARRY_FORWARD_DAYS - 1);
            for (MealSlot slot : slots) {
                if (remaining == 0) break;
                if (slot.getDate().isAfter(lastUseful)) break;
                if (!slot.isHome()) continue;
                if (!recipe.canCover(slot.getMealType())) continue;

                if (slot.isFilled()) continue;
                if (alreadyOnDay(recipe, slot.getDate(), slots, List.of())) continue;
                slot.setRecipeId(recipeId);
                slot.setCookDate(cooked);
                slotsUsed.merge(recipeId, 1, Integer::sum);
                addTicks(dozenByDay, slot.getDate(), recipe);
                remaining--;
            }
        }
    }

    /**
     * Work out which slots one cook of {@code recipe} on {@code target}'s day would cover, and
     * score it. Returns null if the cook can't actually reach {@code target}.
     */
    private Cook simulate(Recipe recipe, MealSlot target, List<MealSlot> slots,
                          Map<LocalDate, Map<DozenItem, Integer>> dozenByDay,
                          Map<Long, Integer> useCount, Map<Long, Integer> slotsUsed,
                          boolean requiredPass, boolean ignoreDayShort, Set<Long> lastWeekRecipes,
                          LocalDate cookDate) {
        List<MealSlot> covered = new ArrayList<>();

        // The card's servings are this recipe's whole allowance for the week, across every
        // batch — once they're spent it sits out until next week.
        int allowance = recipe.weeklyServings() - slotsUsed.getOrDefault(recipe.getId(), 0);
        if (allowance <= 0) return null;

        for (RecipeYield y : recipe.getYields()) {
            if (!y.isUsable()) continue;
            // A yield's servings are a shared pool: each one goes to a slot of ANY of its meal
            // types, first come first served in date order, so "breakfast or snack" spends once.
            int remaining = Math.min(y.getServings(), allowance - covered.size());
            for (MealSlot s : slots) {
                if (remaining == 0 || covered.size() >= allowance) break;
                if (!y.covers(s.getMealType())) continue;
                // Only spend on the kind of slot this pass is filling.
                if (s.getMealType().isRequired() != requiredPass) continue;
                // Spare servings of an optional dish only land on days that still need help.
                if (!requiredPass && !ignoreDayShort && !dayShort(dozenByDay, s.getDate())) continue;
                if (!s.isHome() || s.isFilled() || covered.contains(s)) continue;
                if (s.getDate().isBefore(cookDate)) continue; // leftovers only roll forward
                if (alreadyOnDay(recipe, s.getDate(), slots, covered)) continue;
                covered.add(s);
                remaining--;
            }
        }

        // A flexible yield can spend its servings elsewhere before reaching the slot we set out
        // to fill; if that happened, this cook isn't a candidate.
        if (!covered.contains(target)) return null;

        // Simulated day totals, so multiple servings on one day don't double-count against the target.
        Map<LocalDate, Map<DozenItem, Integer>> running = new HashMap<>();
        double score = 0;
        int towardUnmet = 0;
        for (MealSlot s : covered) {
            for (DozenItem item : DozenItem.values()) {
                if (!item.isFromRecipes()) continue;
                int add = recipe.tickCount(item);
                if (add == 0) continue;
                int already = dozenByDay.getOrDefault(s.getDate(), Map.of()).getOrDefault(item, 0)
                        + running.getOrDefault(s.getDate(), Map.of()).getOrDefault(item, 0);
                int room = Math.max(0, item.getDailyTarget() - already);
                int toward = Math.min(add, room);
                towardUnmet += toward;
                score += toward + (add - toward) * SURPLUS_WEIGHT;
                running.computeIfAbsent(s.getDate(), d -> new HashMap<>()).merge(item, add, Integer::sum);
            }
        }
        score *= Math.pow(1 - REPEAT_DECAY, useCount.getOrDefault(recipe.getId(), 0));
        // Cooked last week? Prefer something else, without ruling it out.
        if (lastWeekRecipes.contains(recipe.getId())) score *= 1 - LAST_WEEK_DECAY;

        int requiredCovered = (int) covered.stream().filter(c -> c.getMealType().isRequired()).count();
        return new Cook(recipe, covered, score, RecipeRating.tierOf(recipe.getRating()),
                requiredCovered, useCount.getOrDefault(recipe.getId(), 0), towardUnmet, cookDate);
    }

    /**
     * True if the day still misses at least one recipe-driven Daily Dozen target. This, not
     * "does this cook help anywhere", is what decides whether an optional slot is wanted:
     * a two-serving smoothie that rescues Monday must not also plant one on a Sunday that's
     * already covered.
     */
    /**
     * One appearance per recipe per day. Leftovers are meant to roll into later days, not to
     * turn up again at dinner after being lunch — so a recipe already on a day, whether from
     * this cook or an earlier one, is out for the rest of it.
     */
    private boolean alreadyOnDay(Recipe recipe, LocalDate date, List<MealSlot> slots,
                                 List<MealSlot> covered) {
        for (MealSlot c : covered) {
            if (c.getDate().equals(date)) return true;
        }
        for (MealSlot s : slots) {
            if (s.getDate().equals(date) && recipe.getId().equals(s.getRecipeId())) return true;
        }
        return false;
    }

    /**
     * Hands a batch's spare servings to meals that are still empty. This runs *after* the
     * cooking decisions, so a three-serving dish no longer claims three slots on sight and
     * crowds out dishes that would have brought something new to those days. Leftovers roll
     * forward only, never twice on a day, and never past the recipe's weekly ceiling.
     */
    private void spreadSpareServings(List<MealSlot> slots, Map<Long, Recipe> byId,
                                     Map<Long, Integer> slotsUsed) {
        // Group what's planned into the batches it came from, earliest cook first.
        Map<String, List<MealSlot>> batches = new LinkedHashMap<>();
        for (MealSlot s : slots) {
            if (s.getRecipeId() == null) continue;
            LocalDate cooked = s.getCookDate() == null ? s.getDate() : s.getCookDate();
            batches.computeIfAbsent(s.getRecipeId() + "@" + cooked, k -> new ArrayList<>()).add(s);
        }

        for (Map.Entry<String, List<MealSlot>> e : batches.entrySet()) {
            Long recipeId = Long.valueOf(e.getKey().substring(0, e.getKey().indexOf('@')));
            Recipe recipe = byId.get(recipeId);
            if (recipe == null) continue;
            LocalDate cooked = LocalDate.parse(e.getKey().substring(e.getKey().indexOf('@') + 1));

            int spare = recipe.weeklyServings() - slotsUsed.getOrDefault(recipeId, 0);
            if (spare <= 0) continue;

            for (MealSlot slot : slots) {
                if (spare == 0) break;
                if (!slot.isHome() || slot.isFilled()) continue;
                if (slot.getDate().isBefore(cooked)) continue;
                if (!recipe.canCover(slot.getMealType())) continue;
                if (alreadyOnDay(recipe, slot.getDate(), slots, List.of())) continue;

                slot.setRecipeId(recipeId);
                slot.setCookDate(cooked);
                slotsUsed.merge(recipeId, 1, Integer::sum);
                spare--;
            }
        }
    }

    /**
     * Distinct dishes being made from scratch on this day. Counted per batch, not per meal:
     * a pot cooked once feeds several meals but is one session, and a batch made ahead counts
     * on the day it's cooked even though the meals it fills are later.
     */
    private int batchesOn(LocalDate day, List<MealSlot> slots) {
        Set<String> batches = new HashSet<>();
        for (MealSlot s : slots) {
            if (s.getRecipeId() != null && day.equals(s.getCookDate())) {
                batches.add("m" + s.getRecipeId());
            }
            if (s.getSideRecipeId() != null && day.equals(s.getSideCookDate())) {
                batches.add("s" + s.getSideRecipeId());
            }
        }
        return batches.size();
    }

    /** True when the day still has room to start another dish. */
    private boolean canCookOn(LocalDate day, List<MealSlot> slots) {
        return batchesOn(day, slots) < BATCHES_PER_DAY;
    }

    private boolean dayShort(Map<LocalDate, Map<DozenItem, Integer>> dozenByDay, LocalDate date) {
        Map<DozenItem, Integer> day = dozenByDay.getOrDefault(date, Map.of());
        for (DozenItem item : DozenItem.values()) {
            if (!item.isFromRecipes()) continue;
            if (day.getOrDefault(item, 0) < item.getDailyTarget()) return true;
        }
        return false;
    }

    private void addTicks(Map<LocalDate, Map<DozenItem, Integer>> dozenByDay, LocalDate date, Recipe r) {
        Map<DozenItem, Integer> day = dozenByDay.computeIfAbsent(date, d -> new HashMap<>());
        for (RecipeTick t : r.getTicks()) {
            day.merge(t.getItem(), t.getCount(), Integer::sum);
        }
    }

    /**
     * Works out which slots a hand-picked recipe should fill, starting at {@code target}.
     * A manual choice gets the same treatment as a planned one: extra servings roll forward
     * into later empty meals it suits, capped by the recipe's weekly ceiling and never twice
     * on a day. Only empty, at-home slots are taken — a hand pick never displaces another meal.
     */
    public List<MealSlot> spreadByHand(Recipe recipe, MealSlot target, List<MealSlot> week) {
        List<MealSlot> ordered = new ArrayList<>(week);
        ordered.sort(Comparator.comparing(MealSlot::getDate)
                .thenComparing(s -> s.getMealType().ordinal()));

        List<MealSlot> covered = new ArrayList<>();
        covered.add(target);                       // the slot the user actually clicked

        // What's left of this recipe's week, ignoring the slot we're filling right now.
        int usedElsewhere = 0;
        for (MealSlot s : ordered) {
            if (!s.equals(target) && recipe.getId().equals(s.getRecipeId())) usedElsewhere++;
        }
        int allowance = recipe.weeklyServings() - usedElsewhere;
        if (allowance <= 1) return covered;        // nothing spare to spread

        List<RecipeYield> yields = recipe.getYields().stream().filter(RecipeYield::isUsable).toList();
        int[] remaining = yields.stream().mapToInt(RecipeYield::getServings).toArray();
        // The clicked slot spends one serving from whichever yield covers its meal.
        for (int i = 0; i < yields.size(); i++) {
            if (yields.get(i).covers(target.getMealType()) && remaining[i] > 0) {
                remaining[i]--;
                break;
            }
        }

        for (int i = 0; i < yields.size(); i++) {
            for (MealSlot s : ordered) {
                if (remaining[i] == 0 || covered.size() >= allowance) break;
                if (s.equals(target) || covered.contains(s)) continue;
                if (!yields.get(i).covers(s.getMealType())) continue;
                if (!s.isHome() || s.isFilled()) continue;
                if (s.getDate().isBefore(target.getDate())) continue;   // leftovers roll forward
                if (alreadyOnDay(recipe, s.getDate(), ordered, covered)) continue;
                covered.add(s);
                remaining[i]--;
            }
        }
        return covered;
    }

    /**
     * Picks the cook to use, applying the rating tiers before score.
     * Returns null when nothing can cover the slot.
     */
    private Cook choose(List<Cook> candidates) {
        if (candidates.isEmpty()) return null;

        // Thumbs-down is held back unless it's genuinely the only thing that fits.
        List<Cook> usable = candidates.stream()
                .filter(c -> c.tier() < RecipeRating.LAST_RESORT_TIER)
                .toList();
        if (usable.isEmpty()) usable = candidates;

        // Admit tiers best-first, stopping at the first tier that still has something we
        // haven't already cooked this week. "Exhausted" means used up, not absent — otherwise a
        // lone thumbs-up recipe would take every dinner in the week.
        List<Cook> eligible = new ArrayList<>();
        for (int tier : usable.stream().map(Cook::tier).distinct().sorted().toList()) {
            List<Cook> inTier = usable.stream().filter(c -> c.tier() == tier).toList();
            eligible.addAll(inTier);
            if (inTier.stream().anyMatch(c -> c.priorUses() == 0)) break;
        }

        // Gap exception: a worse-rated recipe earns a look if it covers more required meals
        // than anything already eligible can.
        int bestCover = eligible.stream().mapToInt(Cook::requiredCovered).max().orElse(0);
        for (Cook c : usable) {
            if (!eligible.contains(c) && c.requiredCovered() > bestCover) eligible.add(c);
        }

        return eligible.stream()
                .max(Comparator.comparingDouble((Cook c) -> c.score() * tierWeight(c.tier()))
                        .thenComparingInt(c -> c.covered().size()))
                .orElse(null);
    }

    /** A gentle thumb on the scale once several tiers are in play together. */
    private static double tierWeight(int tier) {
        return switch (tier) {
            case 0 -> 1.0;                     // thumbs up
            case RecipeRating.UNRATED_TIER -> 0.9;
            case 2 -> 0.8;                     // thumbs middle
            default -> 0.6;                    // thumbs down
        };
    }

    private record Cook(Recipe recipe, List<MealSlot> covered, double score,
                        int tier, int requiredCovered, int priorUses, int towardUnmet,
                        LocalDate cookDate) {}
}
