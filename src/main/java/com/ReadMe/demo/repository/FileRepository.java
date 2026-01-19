package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    boolean existsByTitleAndPath(String title, String path);

    // 최근 읽은 파일 (히스토리)
    List<FileEntity> findTop50ByLastReadAtIsNotNullOrderByLastReadAtDesc();

    // 전체 파일 수
    long count();

    // 완독 파일 수 (progress = 1.0)
    long countByProgress(Double progress);

    // 별점 5개 파일 수
    long countByRating(int rating);
}