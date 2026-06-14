package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/subscriptions")
public class SubscriptionController {


    private final SubscriptionService subscriptionService;

    @Value("${google.play.webhook-token:}")
    private String googleWebhookToken;

    // 프리미엄 상태 조회 엔드포인트
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(
            Authentication authentication,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId
    ) {
        UserEntity user = null;
        // 로그인 상태면 userId도 저
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }

        boolean isPremium = subscriptionService.isPremium(user, deviceId);
        return ResponseEntity.ok(Map.of("isPremium", isPremium));
    }

    // 구독 처리 엔드포인트
    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(
            @RequestBody SubscribeRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId
    ) {
        UserEntity user = getAuthenticatedUser(authentication);

        return ResponseEntity.ok(subscriptionService.subscribe(request, user, deviceId));
    }

    // 애플에서 구독 상태 변경 알림 받을 엔드포인트
    @PostMapping("/webhook/apple")
    public ResponseEntity<?> appleWebhook(@RequestBody String payload) {
        // 애플이 JWT 형태로 전송
        // 디코딩해서 notificationType 확인
        // SUBSCRIBED, DID_RENEW → status = ACTIVE, expiresAt 연장
        // EXPIRED, DID_FAIL_TO_RENEW → status = EXPIRED
        // REFUND → status = CANCELLED

        subscriptionService.handleAppleNotification(payload);
        return ResponseEntity.ok().build();
    }

    // 구글에서 구독 상태 변경 알림 받을 엔드포인트
    @PostMapping("/webhook/google")
    public ResponseEntity<?> googleWebhook(
            @RequestParam String token,
            @RequestBody GooglePubSubMessage message
    ) {
        if (googleWebhookToken.isBlank() || !MessageDigest.isEqual(
                googleWebhookToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            return ResponseEntity.status(401).build();
        }

        subscriptionService.handleGoogleNotification(message);
        return ResponseEntity.ok().build(); // 200 꼭 반환해야 재전송 안 함
    }

    private UserEntity getAuthenticatedUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUser();
        }
        return null;
    }
}
