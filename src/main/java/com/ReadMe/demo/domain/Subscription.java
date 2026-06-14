package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

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
    private String productId;

    private Instant startedAt;
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;

    private Boolean autoRenew;

    @Column(columnDefinition = "TEXT")
    private String receipt;

    @Column(unique = true)
    private String purchaseToken;
    private String originalTransactionId;

    private Instant createdAt;
    private Instant updatedAt;

}
