package com.example.mfscreener.controller;

import com.example.mfscreener.model.Scheme;
import com.example.mfscreener.service.NavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NAVController {

    private final NavService navService;

    @GetMapping(path = "/getNAV/{schemeCode}")
    @Operation(summary = "Fetch the latest NAV from AMFI website.")
    public Scheme getScheme(
            @Parameter(description = "scheme Code for mutual fund", example = "120503") @PathVariable(value = "schemeCode") Long schemeCode) {

        return navService.getNav(schemeCode);
    }

    @GetMapping(path = "/getNAV/{schemeCode}/{date}")
    @Operation(summary = "Fetch NAV on date DD-MM-YYYY (or the last working day before DD-MM-YYYY).")
    public Scheme getSchemeNavOnDate(
            @Parameter(description = "scheme Code for mutual fund", example = "120503") @PathVariable Long schemeCode,
            @Parameter(description = "date", example = "20-01-2020") @PathVariable String date) {
        return navService.getNavOnDate(schemeCode, date);
    }

}
