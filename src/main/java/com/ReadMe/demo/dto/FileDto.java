package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.FileEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDto {
    private Long id;
    private String title;
    private String preview;
    private Instant date;
    private Integer rating;
    private String uri;
    private String path;
    private String review;
    private Double progress;
    private String epubCfi;
    private Double anchorRatio;
    private String readingPreview;

    public static FileDto from(FileEntity entity) {
        return FileDto.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .preview(entity.getPreview())
                .date(entity.getDate())
                .rating(entity.getRating())
                .uri(entity.getUri())
                .path(entity.getPath())
                .review(entity.getReview())
                .progress(entity.getProgress())
                .epubCfi(entity.getEpubCfi())
                .anchorRatio(entity.getAnchorRatio())
                .readingPreview(entity.getReadingPreview())
                .build();
    }
}
