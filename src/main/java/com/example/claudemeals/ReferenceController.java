package com.example.claudemeals;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Enum reference data, so the frontend doesn't hardcode the Daily Dozen or meal list. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    @GetMapping("/meals")
    public List<Map<String, Object>> meals() {
        return Arrays.stream(MealType.values())
                .map(m -> Map.<String, Object>of(
                        "name", m.name(), "label", m.getLabel(), "required", m.isRequired()))
                .toList();
    }

    @GetMapping("/dozen")
    public List<Map<String, Object>> dozen() {
        return Arrays.stream(DozenItem.values())
                .map(d -> Map.<String, Object>of(
                        "name", d.name(), "label", d.getLabel(),
                        "dailyTarget", d.getDailyTarget(), "fromRecipes", d.isFromRecipes()))
                .toList();
    }
}
