package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.FileEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FileDto {
    private Long id;
    private String title;
    private String preview;
    private LocalDateTime date;
    private Integer rating;
    private String uri;
    private String path;
    private String review;

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
                .build();
    }
}
