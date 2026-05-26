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
}
