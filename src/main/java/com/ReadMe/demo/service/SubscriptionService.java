package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.GoogleSubscriptionPurchase;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.exception.SubscriptionOwnedByAnotherAccountException;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository repo;
    private final GooglePlaySubscriptionClient googlePlayClient;
    private final ObjectMapper objectMapper;

    /**
     * 프리미엄 권한 계산.
     *
     * 원칙: 계정에 연결된 구독은 그 계정의 것이고, 계정에 연결되지 않은 비회원 구독만 기기의 것이다.
     * - 로그인 사용자: 내 계정 구독 + 이 기기에서 비회원으로 결제해 아직 계정에 연결되지 않은 구독
     *   (게스트로 결제한 뒤 회원가입/로그인한 사용자가 결제한 권한을 잃지 않도록)
     * - 비회원: 이 기기의 비회원 구독만. 계정 구독은 로그아웃하면 따라오지 않는다.
     *   (공용 기기에서 로그아웃한 뒤 남의 계정 구독으로 프리미엄이 되는 것을 막는다)
     */
    @Transactional(readOnly = true)
    public boolean isPremium(UserEntity user, String deviceId) {
        Instant now = Instant.now();

        if (user != null && isActive(repo.findTopByUserAndStatusOrderByExpiresAtDesc(user, SubscriptionStatus.ACTIVE), now)) {
            return true;
        }

        return deviceId != null && !deviceId.isBlank() && isActive(
                repo.findTopByDeviceIdAndUserIsNullAndStatusOrderByExpiresAtDesc(deviceId, SubscriptionStatus.ACTIVE),
                now
        );
    }

    private boolean isActive(Optional<Subscription> subscription, Instant now) {
        return subscription
                .map(Subscription::getExpiresAt)
                .map(expiresAt -> expiresAt.isAfter(now))
                .orElse(false);
    }

    /**
     * 게스트로 결제한 구독을 로그인한 계정으로 승계한다.
     * 로그인 시 파일/폴더만 연결하고 구독은 놓쳐서 결제한 권한이 사라지던 문제를 막는다.
     */
    @Transactional
    public int linkDeviceSubscriptionsToUser(String deviceId, UserEntity user) {
        if (user == null || deviceId == null || deviceId.isBlank()) {
            return 0;
        }

        List<Subscription> orphans = repo.findByDeviceIdAndUserIsNull(deviceId);
        orphans.forEach(subscription -> {
            subscription.setUser(user);
            subscription.setUpdatedAt(Instant.now());
        });

        if (!orphans.isEmpty()) {
            repo.saveAll(orphans);
            log.info("게스트 구독 {}건을 계정에 연결했습니다. userId={}", orphans.size(), user.getId());
        }
        return orphans.size();
    }

    /**
     * 회원 탈퇴 시 구독과 계정의 연결을 끊는다.
     *
     * Google Play 구독은 앱 계정을 지워도 해지되지 않는다(사용자가 Play 스토어에서 직접 해지한다).
     * 결제 기록은 전자상거래법상 보관 대상이라 지우지 않고 계정 연결만 끊어 비회원 구독으로 되돌린다.
     * 그래야 같은 Google 계정으로 새로 가입하거나 비회원으로 쓸 때 구매 복원이 된다.
     */
    @Transactional
    public void detachSubscriptionsFromUser(UserEntity user) {
        List<Subscription> owned = repo.findByUser(user);
        owned.forEach(subscription -> {
            subscription.setUser(null);
            subscription.setUpdatedAt(Instant.now());
        });

        if (!owned.isEmpty()) {
            repo.saveAll(owned);
            log.info("탈퇴한 계정의 구독 {}건을 계정에서 분리했습니다. userId={}", owned.size(), user.getId());
        }
    }

    @Transactional
    public Map<String, Object> subscribe(SubscribeRequest req, UserEntity user, String deviceId) {
        validateSubscribeRequest(req, user, deviceId);

        GoogleSubscriptionPurchase purchase = googlePlayClient.getSubscription(req.getPurchaseToken());
        log.info(
                "Google Play 구독 검증 완료. requestedProductId={}, verifiedProductId={}, state={}, acknowledgementState={}",
                req.getProductId(),
                purchase.productId(),
                purchase.subscriptionState(),
                purchase.acknowledgementState()
        );
        if (!req.getProductId().equals(purchase.productId())) {
            throw new IllegalArgumentException("구매한 상품과 요청한 상품이 일치하지 않습니다.");
        }
        if (!purchase.isEntitled(Instant.now())) {
            throw new IllegalArgumentException("활성화할 수 없는 Google Play 구독입니다.");
        }

        Subscription subscription = repo.findByPurchaseToken(req.getPurchaseToken())
                .map(existing -> {
                    validateTokenOwner(existing, user);
                    return existing;
                })
                .orElseGet(Subscription::new);

        if (purchase.needsAcknowledgement()) {
            googlePlayClient.acknowledge(purchase.productId(), req.getPurchaseToken());
        }

        expireLinkedPurchase(purchase.linkedPurchaseToken());
        expireOtherActiveSubscriptions(user, deviceId, subscription);

        subscription.setUser(user);
        subscription.setDeviceId(deviceId);
        subscription.setPlatform(Platform.ANDROID);
        subscription.setPlanType(req.getPlanType());
        subscription.setProductId(purchase.productId());
        subscription.setPurchaseToken(req.getPurchaseToken());
        applyGoogleState(subscription, purchase);
        repo.save(subscription);

        return Map.of(
                "isPremium", true,
                "expiresAt", subscription.getExpiresAt(),
                "autoRenew", subscription.getAutoRenew()
        );
    }

    @Transactional
    public void handleGoogleNotification(GooglePubSubMessage message) {
        String purchaseToken = extractPurchaseToken(message);
        if (purchaseToken == null) {
            return;
        }

        Optional<Subscription> existing = repo.findByPurchaseToken(purchaseToken);
        if (existing.isEmpty()) {
            log.info("앱 등록 API보다 먼저 도착한 Google Play 구매 RTDN입니다. 앱 등록 요청에서 처리합니다.");
            return;
        }

        GoogleSubscriptionPurchase purchase = googlePlayClient.getSubscription(purchaseToken);
        Subscription subscription = existing.get();

        if (purchase.needsAcknowledgement() && purchase.isEntitled(Instant.now())) {
            googlePlayClient.acknowledge(purchase.productId(), purchaseToken);
        }

        expireLinkedPurchase(purchase.linkedPurchaseToken());
        subscription.setProductId(purchase.productId());
        applyGoogleState(subscription, purchase);
        repo.save(subscription);
    }

    @Transactional
    public void synchronizeGoogleSubscription(Long subscriptionId) {
        Subscription subscription = repo.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("구독 정보를 찾을 수 없습니다."));
        if (subscription.getPurchaseToken() == null
                || subscription.getPurchaseToken().isBlank()) {
            return;
        }

        GoogleSubscriptionPurchase purchase =
                googlePlayClient.getSubscription(subscription.getPurchaseToken());
        if (purchase.needsAcknowledgement() && purchase.isEntitled(Instant.now())) {
            googlePlayClient.acknowledge(purchase.productId(), subscription.getPurchaseToken());
        }
        expireLinkedPurchase(purchase.linkedPurchaseToken());
        subscription.setPlatform(Platform.ANDROID);
        subscription.setProductId(purchase.productId());
        applyGoogleState(subscription, purchase);
        repo.save(subscription);
    }

    public void handleAppleNotification(String payload) {
        throw new UnsupportedOperationException("Apple 구독 검증은 아직 지원하지 않습니다.");
    }

    private void validateSubscribeRequest(SubscribeRequest req, UserEntity user, String deviceId) {
        if (user == null && (deviceId == null || deviceId.isBlank())) {
            throw new IllegalArgumentException("유저 또는 X-Device-Id 정보가 필요합니다.");
        }
        if (req == null || req.getPlatform() == null) {
            throw new IllegalArgumentException("결제 플랫폼이 필요합니다.");
        }
        if (req.getPlatform() != Platform.ANDROID) {
            throw new UnsupportedOperationException("Apple 구독 검증은 아직 지원하지 않습니다.");
        }
        if (req.getPurchaseToken() == null || req.getPurchaseToken().isBlank()) {
            throw new IllegalArgumentException("Google Play 구매 토큰이 필요합니다.");
        }
        if (req.getProductId() == null || req.getProductId().isBlank()) {
            throw new IllegalArgumentException("Google Play 상품 ID가 필요합니다.");
        }
    }

    /**
     * 이미 등록된 구매 토큰을 다시 보낸 요청자가 가져가도 되는지 확인한다.
     *
     * - 계정에 연결된 구독: 같은 계정만 허용한다. 다른 계정이나 비회원이면 409 로 "원래 계정으로 로그인"을 안내한다.
     * - 비회원 구독: 요청자에게 넘긴다(구매 복원). 앱을 재설치하면 deviceId 가 새로 생기므로
     *   기기로 막으면 결제한 사람이 복원할 방법이 없다. 구매 토큰은 결제한 Google 계정이 로그인된 기기의
     *   Play 결제 라이브러리에서만 받을 수 있고 서버가 Google 에 유효성까지 확인했으므로, 토큰을 가진 쪽을 구매자로 본다.
     */
    private void validateTokenOwner(Subscription subscription, UserEntity user) {
        UserEntity owner = subscription.getUser();
        if (owner == null) {
            return;
        }
        if (user == null || !owner.getId().equals(user.getId())) {
            throw new SubscriptionOwnedByAnotherAccountException(
                    "이미 다른 계정에 연결된 구독입니다. 구독한 계정으로 로그인해 주세요."
            );
        }
    }

    /**
     * 같은 소유자(계정, 또는 비회원 기기)의 이전 활성 구독만 만료 처리한다.
     * 기기 기준으로 넓게 잡으면 같은 기기를 쓰는 다른 계정의 유효한 구독까지 만료시킨다.
     */
    private void expireOtherActiveSubscriptions(UserEntity user, String deviceId, Subscription current) {
        List<Subscription> activeSubscriptions;
        if (user != null) {
            activeSubscriptions = repo.findByUserAndStatus(user, SubscriptionStatus.ACTIVE);
        } else if (deviceId != null && !deviceId.isBlank()) {
            activeSubscriptions = repo.findByDeviceIdAndUserIsNullAndStatus(deviceId, SubscriptionStatus.ACTIVE);
        } else {
            return;
        }

        activeSubscriptions.stream()
                .filter(subscription -> subscription.getId() != null)
                .filter(subscription -> !subscription.getId().equals(current.getId()))
                .forEach(subscription -> subscription.setStatus(SubscriptionStatus.EXPIRED));
    }

    private void expireLinkedPurchase(String linkedPurchaseToken) {
        if (linkedPurchaseToken == null || linkedPurchaseToken.isBlank()) {
            return;
        }

        repo.findByPurchaseToken(linkedPurchaseToken)
                .ifPresent(subscription -> subscription.setStatus(SubscriptionStatus.EXPIRED));
    }

    private void applyGoogleState(Subscription subscription, GoogleSubscriptionPurchase purchase) {
        Instant now = Instant.now();
        subscription.setStartedAt(purchase.startedAt());
        subscription.setExpiresAt(purchase.expiresAt());
        subscription.setAutoRenew(purchase.autoRenew());
        subscription.setStatus(purchase.isEntitled(now)
                ? SubscriptionStatus.ACTIVE
                : SubscriptionStatus.EXPIRED);

        if (subscription.getCreatedAt() == null) {
            subscription.setCreatedAt(now);
        }
        subscription.setUpdatedAt(now);
    }

    private String extractPurchaseToken(GooglePubSubMessage message) {
        try {
            if (message == null || message.getMessage() == null
                    || message.getMessage().getData() == null) {
                throw new IllegalArgumentException("Google RTDN 메시지 데이터가 없습니다.");
            }

            String decoded = new String(
                    Base64.getDecoder().decode(message.getMessage().getData()),
                    StandardCharsets.UTF_8
            );
            JsonNode root = objectMapper.readTree(decoded);
            JsonNode notification = root.path("subscriptionNotification");
            if (notification.isMissingNode()) {
                log.info("구독 상태 변경이 아닌 Google RTDN 메시지를 무시합니다.");
                return null;
            }
            return notification.path("purchaseToken").asText(null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Google RTDN 메시지를 읽을 수 없습니다.", e);
        }
    }
}
