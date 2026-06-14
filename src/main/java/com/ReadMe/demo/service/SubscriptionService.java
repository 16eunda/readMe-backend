package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.GoogleSubscriptionPurchase;
import com.ReadMe.demo.dto.SubscribeRequest;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository repo;
    private final GooglePlaySubscriptionClient googlePlayClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public boolean isPremium(UserEntity user, String deviceId) {
        if (user == null && (deviceId == null || deviceId.isBlank())) {
            return false;
        }

        Optional<Subscription> sub = user != null
                ? repo.findTopByUserAndStatusOrderByExpiresAtDesc(user, SubscriptionStatus.ACTIVE)
                : repo.findTopByDeviceIdAndStatusOrderByExpiresAtDesc(deviceId, SubscriptionStatus.ACTIVE);

        return sub
                .map(Subscription::getExpiresAt)
                .map(expiresAt -> expiresAt.isAfter(Instant.now()))
                .orElse(false);
    }

    @Transactional
    public Map<String, Object> subscribe(SubscribeRequest req, UserEntity user, String deviceId) {
        validateSubscribeRequest(req, user, deviceId);

        GoogleSubscriptionPurchase purchase = googlePlayClient.getSubscription(req.getPurchaseToken());
        if (!req.getProductId().equals(purchase.productId())) {
            throw new IllegalArgumentException("구매한 상품과 요청한 상품이 일치하지 않습니다.");
        }
        if (!purchase.isEntitled(Instant.now())) {
            throw new IllegalArgumentException("활성화할 수 없는 Google Play 구독입니다.");
        }

        Subscription subscription = repo.findByPurchaseToken(req.getPurchaseToken())
                .map(existing -> {
                    validateTokenOwner(existing, user, deviceId);
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
            log.warn("등록되지 않은 Google Play 구매 토큰의 RTDN을 무시합니다.");
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

    private void validateTokenOwner(Subscription subscription, UserEntity user, String deviceId) {
        if (subscription.getUser() != null) {
            if (user == null || !subscription.getUser().getId().equals(user.getId())) {
                throw new IllegalArgumentException("이미 다른 사용자에게 등록된 구매 토큰입니다.");
            }
            return;
        }

        if (subscription.getDeviceId() != null && !subscription.getDeviceId().equals(deviceId)) {
            throw new IllegalArgumentException("이미 다른 기기에 등록된 구매 토큰입니다.");
        }
    }

    private void expireOtherActiveSubscriptions(UserEntity user, String deviceId, Subscription current) {
        Set<Subscription> activeSubscriptions = new LinkedHashSet<>();
        if (user != null) {
            activeSubscriptions.addAll(repo.findByUserAndStatus(user, SubscriptionStatus.ACTIVE));
        }
        if (deviceId != null && !deviceId.isBlank()) {
            activeSubscriptions.addAll(repo.findByDeviceIdAndStatus(deviceId, SubscriptionStatus.ACTIVE));
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
