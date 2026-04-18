package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.FileEntity;
import lombok.*;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResponse {

    // 추천 파일 목록
    private List<FileDto> recommendations;

    // 추천 품질 (프론트에서 메시지 분기용)
    // GOOD: 분석 데이터 충분, 정확한 추천
    // LOW_DATA: 분석된 파일이 적어서 랜덤 포함됨
    // NO_DATA: 분석된 파일 없음, 전부 랜덤
    private String quality;

    // 프론트에 표시할 메시지 (선택적으로 사용)
    private String message;

    // 분석된 파일 수 / 전체 파일 수 (프론트에서 진행률 표시 가능)
    private long analyzedCount;
    private long totalCount;
}
