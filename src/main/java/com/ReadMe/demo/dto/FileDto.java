package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.FileEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDto {
    private Long id;
    private String title;
    private String preview;
    private LocalDateTime date;
    private Integer rating;
    private String uri;
    private String path;
    private String review;
    private Double progress;   // ★ 0~1 진행도 저장 (txt: 0~1, epub: cfi)
    private String epubCfi;         // ★ 마지막 읽은 위치 저장 (epub cfi)

    // getter, setter 또는 @Data (Lombok)
    // Entity → Dto 변환 메서드
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
                .build();
    }
}
