package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.FileEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInfoResponse {

    private String genre;       // 장르 (판타지, 로맨스 등)
    private String keywords;    // 키워드 (쉼표 구분)
    private String mood;        // 분위기
    private String summary;     // 한 줄 요약
    private String target;      // 추천 독자 유형
    private String analysisStatus; // DONE, PENDING, FAILED, SKIPPED

    public static AiInfoResponse from(FileEntity file) {
        return AiInfoResponse.builder()
                .genre(file.getAiGenre())
                .keywords(file.getAiKeywords())
                .mood(file.getAiMood())
                .summary(file.getAiSummary())
                .target(file.getAiTarget())
                .analysisStatus(file.getAnalysisStatus())
                .build();
    }

    // 아직 분석 안 된 경우
    public static AiInfoResponse notAnalyzed() {
        return AiInfoResponse.builder()
                .analysisStatus("PENDING")
                .build();
    }

    // 분석 큐에 들어간 경우 (프리미엄 유저, 잠시 후 완료됨)
    public static AiInfoResponse analyzing() {
        return AiInfoResponse.builder()
                .analysisStatus("QUEUED")
                .build();
    }

    // 프리미엄 필요
    public static AiInfoResponse notAvailable() {
        return AiInfoResponse.builder()
                .analysisStatus("PREMIUM_REQUIRED")
                .build();
    }
}
