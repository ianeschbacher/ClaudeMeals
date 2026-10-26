package com.example.claudemeals;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface MealSlotRepository extends JpaRepository<MealSlot, Long> {
    List<MealSlot> findByDateBetweenOrderByDateAsc(LocalDate start, LocalDate end);

    /** Every slot pointing at a recipe, as either its main course or its side. */
    List<MealSlot> findByRecipeIdOrSideRecipeId(Long recipeId, Long sideRecipeId);
}
