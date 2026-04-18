package com.ReadMe.demo.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Table(
        name = "files",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"title", "path"})
        },
        indexes = {
            // 핵심! 지금 쿼리를 빠르게 하는 복합 인덱스
            @Index(name = "idx_file_device_user", columnList = "device_id, user_id"),

            // 파일 경로 검색용 (중복 체크 등)
            @Index(name = "idx_file_path", columnList = "path"),

            // user_id 기준 조회용 (특정 유저의 파일 목록 불러올 때)
            @Index(name = "idx_file_user", columnList = "user_id")
        }
)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 추가 필드
    @Column(nullable = true)
    private String deviceId;  // 디바이스 ID

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String path;
    private String uri;
    private String preview; // 미리보기
    private String review;
    private LocalDateTime date;
    private int rating;
    private Double progress = 0.0;   // ★ 0~1 진행도 저장 (txt: 0~1, epub: cfi)
    private String epubCfi;         // ★ 마지막 읽은 위치 저장 (epub cfi)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType type;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    @Column(nullable = false)
    private boolean completed;

    @Column
    private String analysisStatus; // PROCESSING, DONE, FAILED

    @Column
    private String aiGenre;     // "로맨스", "판타지" 등

    @Column(length = 500)
    private String aiMood;      // "감성적,설렘"

    @Column(length = 500)
    private String aiKeywords;  // "사랑,학교,청춘"

    @Lob
    private String aiContent;   // AI가 찾은 책 내용 설명

    @Column(length = 500)
    private String aiTarget;    // "10대,여성"

    @Column(length = 2000)
    private String aiSummary;   // 한 줄 요약

    private LocalDateTime aiAnalyzedAt; // AI 분석 완료 시간

    @Column
    private String normalizedTitle; // 확장자 제거된 제목

    // 파일 읽기 로그와 일대다 관계 설정
    // 파일 삭제시 연관된 읽기 로그도 함께 삭제되도록 설정
    @OneToMany(mappedBy = "file", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    @Builder.Default
    private List<FileReadLog> readLogs = new ArrayList<>();


    // 여기 추가
    @ManyToOne
    @JoinColumn(name = "user_id") // DB FK 컬럼
    private UserEntity user;
}