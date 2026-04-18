package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_analysis_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAnalysisLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 누가 사용했는지 (로그인 유저)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    // 게스트용
    private String deviceId;

    // 어떤 파일을 분석했는지
    private Long fileId;

    // 분석 요청 시간
    private LocalDateTime analyzedAt;
}
