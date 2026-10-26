package com.example.claudemeals;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/dozen-gaps")
public class DozenGapController {

    private final DozenGapService gaps;
    private final WeekService weeks;

    public DozenGapController(DozenGapService gaps, WeekService weeks) {
        this.gaps = gaps;
        this.weeks = weeks;
    }

    @GetMapping
    public DozenGapView gaps(@RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        return gaps.analyse(start == null ? weeks.currentPeriodStart() : start);
    }
}
