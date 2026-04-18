package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.dto.SubscriptionResponse;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository repo;

    // 프리미엄 여부 확인
    public boolean isPremium(UserEntity user, String deviceId) {

        if (user == null && deviceId == null) {
            return false;
        }

        // 로그인 유저는 user 기준, 게스트는 deviceId 기준으로 활성 구독 조회
        Optional<Subscription> sub = (user != null)
                ? repo.findTopByUserAndStatusOrderByExpiresAtDesc(user, SubscriptionStatus.ACTIVE)
                : repo.findTopByDeviceIdAndStatusOrderByExpiresAtDesc(deviceId, SubscriptionStatus.ACTIVE);

        return sub
                .map(s -> s.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    // 구독 정보 조회
    public Subscription getActiveSubscription(UserEntity user, String deviceId) {
        // 기존 로직
        if (user == null && deviceId == null) {
            return null;
        }

        Optional<Subscription> sub = (user != null)
                ? repo.findTopByUserAndStatusOrderByExpiresAtDesc(user, SubscriptionStatus.ACTIVE)
                : repo.findTopByDeviceIdAndStatusOrderByExpiresAtDesc(deviceId, SubscriptionStatus.ACTIVE);

        return sub.orElse(null);
    }

    // 구독 처리
    // 프론트에서 영수증(receipt) 또는 구매 토큰(purchaseToken)과 함께 구독 요청
    public Map subscribe(SubscribeRequest req, UserEntity user, String deviceId) {
        // user/deviceId 확인 (둘 다 없으면 예외)
        if (user == null && deviceId == null) {
            throw new RuntimeException("유저 정보 없음");
        }

        // 기존 active 구독 종료
        if (user != null) {
            repo.findByUserAndStatus(user, SubscriptionStatus.ACTIVE)
                    .forEach(s -> {
                        s.setStatus(SubscriptionStatus.EXPIRED);
                        repo.save(s);
                    });
        } else {
            repo.findByDeviceIdAndStatus(deviceId, SubscriptionStatus.ACTIVE)
                    .forEach(s -> {
                        s.setStatus(SubscriptionStatus.EXPIRED);
                        repo.save(s);
                    });
        }
        String transactionId = "";
        // ios/안드로이드 구분 → 영수증/구매 토큰 검증
        if (req.getPlatform() == Platform.IOS){
            // 1. 애플 서버에 영수증 검증 요청
            transactionId = verifyAppleReceipt(req.getReceipt());
            if (transactionId.isEmpty()) throw new RuntimeException("유효하지 않은 영수증");
        } else {
            // 2. 구글 서버에 purchaseToken 검증
            boolean valid = verifyGooglePurchase(req.getPurchaseToken());
            if (!valid) throw new RuntimeException("유효하지 않은 구매");
        }

        // 3. DB에 구독 저장
        Subscription sub = new Subscription();
        if (user != null) {
            sub.setUser(user);
        } else {
            sub.setDeviceId(deviceId);
        }

        // iOS는 영수증, 안드로이드는 구매 토큰 저장
        if (req.getPlatform() == Platform.ANDROID) {
            System.out.println("purchaseToken: " + req.getPurchaseToken());
            sub.setPurchaseToken(req.getPurchaseToken());
        } else {
            sub.setReceipt(req.getReceipt());
            sub.setOriginalTransactionId(transactionId);
        }

        sub.setPlanType(req.getPlanType());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartedAt(LocalDateTime.now());
        sub.setExpiresAt(req.getPlanType().equals("monthly")
                ? LocalDateTime.now().plusMonths(1)
                : LocalDateTime.now().plusYears(1));
        repo.save(sub);

        // 4. 응답
        return Map.of("isPremium", true);
    }

    private String verifyAppleReceipt(String receipt) {
        // 애플 서버에 POST 요청
        String url = "https://buy.itunes.apple.com/verifyReceipt"; // 프로덕션
        // 실패하면 샌드박스로 재시도: https://sandbox.itunes.apple.com/verifyReceipt

        Map<String, String> body = Map.of(
                "receipt-data", receipt,
                "password", "앱스토어_공유_비밀키" // App Store Connect에서 발급
        );

        // HTTP POST → 응답의 status == 0 이면 유효
        // RestTemplate or WebClient 사용

        return "test";
    }

    private boolean verifyGooglePurchase(String purchaseToken) {
        // Google Play Developer API 사용
        // GET https://androidpublisher.googleapis.com/androidpublisher/v3/
        //     applications/{packageName}/purchases/subscriptions/{subscriptionId}/tokens/{token}
        // → 응답의 paymentState == 1 이면 유효

        try {
            String packageName = "com.your.app"; // 앱 패키지명
            String subscriptionId = "premium_monthly"; // 상품 ID

            String url = String.format(
                    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/subscriptions/%s/tokens/%s",
                    packageName,
                    subscriptionId,
                    purchaseToken
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getAccessToken()); // ⭐ 중요

            HttpEntity<String> entity = new HttpEntity<>(headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(response.getBody());

            int paymentState = json.get("paymentState").asInt();

            return paymentState == 1;

        } catch (Exception e) {
            return false;
        }
    }

    // Google API 호출 시 필요한 액세스 토큰 발급
    private String getAccessToken() throws Exception {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream("service-account.json"))
                .createScoped(List.of("https://www.googleapis.com/auth/androidpublisher"));

        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    // 애플 서버에서 구독 상태 변경 알림 처리
    public void handleAppleNotification(String payload) {
        // JWT 디코딩 → notificationType 확인
        // SUBSCRIBED, DID_RENEW → status = ACTIVE, expiresAt 연장
        // EXPIRED, DID_FAIL_TO_RENEW → status = EXPIRED
        // REFUND → status = CANCELLED

        // 구현 생략 (구글과 유사한 흐름)
    }

    // 구글 서버에서 구독 상태 변경 알림 처리
    public void handleGoogleNotification(GooglePubSubMessage message) {
        try {
            // 1. base64 디코딩
            String decoded = new String(
                    Base64.getDecoder().decode(message.getMessage().getData())
            );

            // 2. JSON 파싱
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(decoded);

            JsonNode subNoti = root.get("subscriptionNotification");

            int type = subNoti.get("notificationType").asInt();
            String purchaseToken = subNoti.get("purchaseToken").asText();

            // 3. 구독 조회
            Subscription sub = repo.findByPurchaseToken(purchaseToken)
                    .orElseThrow(() -> new RuntimeException("구독 없음"));

            // 4. 상태 변경
            switch (type) {
                case 1: // RENEWED
                case 2: // PURCHASED
                    sub.setStatus(SubscriptionStatus.ACTIVE);
                    sub.setExpiresAt(LocalDateTime.now().plusMonths(1));
                    break;

                case 3: // CANCELED
                    sub.setStatus(SubscriptionStatus.CANCELLED);
                    break;

                case 13: // EXPIRED
                    sub.setStatus(SubscriptionStatus.EXPIRED);
                    break;
            }

            repo.save(sub);

        } catch (Exception e) {
            throw new RuntimeException("구글 웹훅 처리 실패", e);
        }
    }
}