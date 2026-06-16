package com.ReadMe.demo.dto;

import lombok.*;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResponse {

    private List<RecFileDto> recommendations;
    private String quality;
    private String message;
    private long analyzedCount;
    private long totalCount;
    private boolean premiumRequired;

    public static RecommendationResponse premiumRequired() {
        return RecommendationResponse.builder()
                .recommendations(List.of())
                .quality("PREMIUM_REQUIRED")
                .message("프리미엄 구독이 필요한 기능입니다.")
                .analyzedCount(0)
                .totalCount(0)
                .premiumRequired(true)
                .build();
    }
}
