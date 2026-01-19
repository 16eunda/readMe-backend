package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FileReadLog;
import com.ReadMe.demo.dto.FileRankingDto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FileReadLogRepository extends JpaRepository<FileReadLog, Long> {

    @Query("""
        SELECT new com.ReadMe.demo.dto.FileRankingDto(
            f.id,
            f.title,
            COUNT(r.id),
            f.progress,
            f.rating,
            MAX(r.readAt)
        )
        FROM FileReadLog r
        JOIN r.file f
        WHERE r.readAt >= :from
        GROUP BY f.id, f.title, f.progress, f.rating
        ORDER BY COUNT(r.id) DESC
    """)
    List<FileRankingDto> findRankingSince(
            @Param("from") LocalDateTime from,
            Pageable pageable
    );
}
