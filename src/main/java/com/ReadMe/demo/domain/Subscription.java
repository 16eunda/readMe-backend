package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions", indexes = {
        @Index(name = "idx_expires_at", columnList = "expiresAt")
})
@Getter
@Setter
public class Subscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    private String deviceId;

    @Enumerated(EnumType.STRING)
    private Platform platform;

    private String planType;

    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;

    private Boolean autoRenew;

    @Column(columnDefinition = "TEXT")
    private String receipt;

    private String purchaseToken;
    private String originalTransactionId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}

