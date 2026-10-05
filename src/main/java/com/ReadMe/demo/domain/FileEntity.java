package com.ReadMe.demo.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
// 바뀐 컬럼만 UPDATE 한다. 읽던 위치 저장과 AI 분석 결과 저장이 동시에 일어나도 서로의 컬럼을 옛 값으로 덮어쓰지 않게 한다.
@DynamicUpdate
@Getter
@Table(
        name = "files",
        indexes = {
            // 핵심! 지금 쿼리를 빠르게 하는 복합 인덱스
            @Index(name = "idx_file_device_user", columnList = "device_id, user_id"),

            // 파일 경로 검색용 (중복 체크 등)
            @Index(name = "idx_file_path", columnList = "path"),

            // user_id 기준 조회용 (특정 유저의 파일 목록 불러올 때)
            @Index(name = "idx_file_user", columnList = "user_id"),

            @Index(name = "idx_file_user_path_date_id", columnList = "user_id, path, date, id"),
            @Index(name = "idx_file_user_path_rating_id", columnList = "user_id, path, rating, id"),
            @Index(name = "idx_file_device_path_date_id", columnList = "device_id, path, date, id"),
            @Index(name = "idx_file_device_path_rating_id", columnList = "device_id, path, rating, id"),

            // 1분마다 도는 분석 대기 점검(findAnalysisBacklogIds)이 분석 완료(DONE)된 책을 훑지 않고 남은 책만 찾게 한다.
            @Index(name = "idx_file_analysis_status", columnList = "analysis_status")
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
    @Column(columnDefinition = "TEXT")
    private String readingPreview; // 읽는 중 미리보기 (txt: 현재 페이지 텍스트, epub: 현재 cfi 위치)
    // 앱 입력창은 300자까지 받는다. 기본 길이(255)면 256자부터 저장에 실패해 리뷰가 사라졌다.
    // ddl-auto=update 는 기존 컬럼 길이를 바꾸지 않으므로 운영 DB 는 직접 늘린다:
    //   ALTER TABLE files ALTER COLUMN review TYPE varchar(1000);
    @Column(length = 1000)
    private String review;
    @Column(nullable = false)
    private Instant date;
    private int rating;
    @Builder.Default
    private Double progress = 0.0;   // ★ 0~1 진행도 저장 (txt: 0~1, epub: cfi)
    private String epubCfi;         // ★ 마지막 읽은 위치 저장 (epub cfi)
    @Builder.Default
    private Double anchorRatio = 0.5;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType type;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;

    @Column(nullable = false)
    private boolean completed;

    // PENDING(아직 분석 안 함) → PROCESSING(분석 중) → DONE(완료)
    // 재시도 대상: FAILED(실패), LIMIT_EXCEEDED(오늘 자동 분석 한도 초과)
    // QUEUED 는 예전 버전이 남긴 값이다. PENDING 과 똑같이 분석 대상으로 본다.
    @Column
    private String analysisStatus;

    // 마지막으로 분석을 시작한 시각. 서버가 분석 도중 꺼져 PROCESSING 으로 남은 파일과 하루 한 번 재시도할 파일을 가려낸다.
    private LocalDateTime analysisStartedAt;

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
