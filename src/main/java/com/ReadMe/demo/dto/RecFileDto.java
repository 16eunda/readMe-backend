package com.ReadMe.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 로직용 경량 DTO - @Lob(aiContent) 제외
 * RecService에서 추천 파일 목록에 사용
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RecFileDto {
    private Long id;
    private String title;
    private String uri;
    private String path;
    private String aiGenre;
    private String aiKeywords;
    private Double progress;
    private Integer rating;
}
