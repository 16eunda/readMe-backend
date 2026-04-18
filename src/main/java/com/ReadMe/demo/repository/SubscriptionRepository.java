package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    // 로그인 유저 활성 구독 조회
    Optional<Subscription> findTopByUserAndStatusOrderByExpiresAtDesc(
            UserEntity user, SubscriptionStatus status);

    // 게스트 활성 구독 조회
    Optional<Subscription> findTopByDeviceIdAndStatusOrderByExpiresAtDesc(
            String deviceId, SubscriptionStatus status);

    List<Subscription> findByUserAndStatus(UserEntity user, SubscriptionStatus subscriptionStatus);

    List<Subscription> findByDeviceIdAndStatus(String deviceId, SubscriptionStatus subscriptionStatus);

    Optional<Subscription> findByPurchaseToken(String purchaseToken);
}
