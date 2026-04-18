package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DialectOverride;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")  // 소프트 삭제 적용
public class UserEntity {
    @Id
    @GeneratedValue
    private Long id;

    @Column(unique = true)
    private String username;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // null = 활성, 값 있으면 탈퇴

    private String password;  // 해시 저장
    private String email;
    private LocalDateTime createdAt;
}