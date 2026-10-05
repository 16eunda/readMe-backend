package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 로그인 세션. 로그인할 때마다 하나 만들고, refreshToken 에 이 id(sid)를 넣는다.
 * - 재발급은 세션이 살아 있을 때만 해 주고, 그때마다 만료를 30일 뒤로 늦춘다. (쓰는 동안 로그인 유지)
 * - 로그아웃하면 세션을 지운다. 그 세션으로 발급된 refreshToken 은 옛것·새것 모두 바로 쓸 수 없게 된다.
 * - 회원 탈퇴하면 그 계정의 세션을 모두 지운다.
 * accessToken(1시간)은 세션을 보지 않는다.
 */
@Entity
@Table(name = "refresh_sessions", indexes = {
        @Index(name = "idx_refresh_session_user", columnList = "user_id"),
        @Index(name = "idx_refresh_session_expires", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RefreshSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
