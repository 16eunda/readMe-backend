package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    // 로그인 유저 활성 구독 조회
    Optional<Subscription> findTopByUserAndStatusOrderByExpiresAtDesc(
            UserEntity user, SubscriptionStatus status);

    // 아직 어떤 계정에도 연결되지 않은(비회원 상태에서 결제한) 기기 구독 조회.
    // 비회원·로그인 사용자 모두 권한을 계산할 때 이것을 본다. (계정 구독은 기기를 따라가지 않는다)
    Optional<Subscription> findTopByDeviceIdAndUserIsNullAndStatusOrderByExpiresAtDesc(
            String deviceId, SubscriptionStatus status);

    // 로그인 시점에 계정으로 승계할 게스트 구독
    List<Subscription> findByDeviceIdAndUserIsNull(String deviceId);

    List<Subscription> findByUserAndStatus(UserEntity user, SubscriptionStatus subscriptionStatus);

    // 비회원(계정 미연결) 기기 구독
    List<Subscription> findByDeviceIdAndUserIsNullAndStatus(String deviceId, SubscriptionStatus subscriptionStatus);

    // 회원 탈퇴 시 계정 연결을 끊을 구독
    List<Subscription> findByUser(UserEntity user);

    Optional<Subscription> findByPurchaseToken(String purchaseToken);

    List<Subscription> findByPurchaseTokenIsNotNullAndStatus(SubscriptionStatus status);
}
