package com.exittrading.app.controller;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.LiquidityImpactResponse;
import com.exittrading.app.service.impact.LiquidityImpactService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/liquidity-impact")
public class LiquidityImpactController {

    private final LiquidityImpactService service;

    public LiquidityImpactController(LiquidityImpactService service) {
        this.service = service;
    }

    @PostMapping("/compute")
    public List<LiquidityImpactResponse> compute(@RequestBody List<DepthView> payload,
                                                 @RequestParam(name = "stage", required = false) String stage,
                                                 @RequestParam(name = "auctionPhase", required = false) String auctionPhase,
                                                 @RequestParam(name = "tick", required = false) Double tickOverride,
                                                 @RequestParam(name = "qref", required = false) Long qrefOverride) {
        return service.compute(payload, stage, auctionPhase, tickOverride, qrefOverride);
    }
}
