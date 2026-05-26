package com.ReadMe.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 로직용 경량 DTO - @Lob(aiContent) 제외
 * RecService에서 장르/키워드 분석에만 사용
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FileGenreKeywordDto {
    private Long id;
    private String aiGenre;
    private String aiKeywords;
}
