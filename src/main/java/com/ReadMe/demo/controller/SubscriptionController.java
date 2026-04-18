package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.repository.UserRepository;
import com.ReadMe.demo.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/subscriptions")
public class SubscriptionController {


    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    // 프리미엄 상태 조회 엔드포인트
    @GetMapping("/status")
    public ResponseEntity<?> getStatus(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId
    ) {
        UserEntity user = userDetails != null
                ? userRepository.findByEmail(userDetails.getUsername()).orElse(null)
                : null;

        boolean isPremium = subscriptionService.isPremium(user, deviceId);
        return ResponseEntity.ok(Map.of("isPremium", isPremium));
    }

    // 구독 처리 엔드포인트
    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(
            @RequestBody SubscribeRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId
    ) {
        UserEntity user = userDetails != null
                ? userRepository.findByEmail(userDetails.getUsername()).orElse(null)
                : null;

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
    public ResponseEntity<?> googleWebhook(@RequestBody GooglePubSubMessage message) {
        // Google Pub/Sub으로 전달됨
        // base64 디코딩 → subscriptionNotification 파싱
        // notificationType 1 = 갱신, 3 = 취소, 13 = 만료

        subscriptionService.handleGoogleNotification(message);
        return ResponseEntity.ok().build(); // 200 꼭 반환해야 재전송 안 함
    }
}