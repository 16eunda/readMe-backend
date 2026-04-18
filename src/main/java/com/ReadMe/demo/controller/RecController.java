package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.RecommendationResponse;
import com.ReadMe.demo.service.RecService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/recommendations")
public class RecController {
    private final RecService recService;

    @GetMapping()
    public RecommendationResponse getRecommendations(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        return recService.getRecommendations(authentication, deviceId);
    }
}
