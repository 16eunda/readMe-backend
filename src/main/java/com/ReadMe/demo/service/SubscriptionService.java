package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
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
            boolean valid = verifyGooglePurchase(req.getPurchaseToken(), req.getProductId());
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

    // 애플 영수증 검증
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

    private boolean verifyGooglePurchase(String purchaseToken, String productId) {
        try {
            String packageName = "com.readme.app";

            String url = String.format(
                    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/subscriptions/%s/tokens/%s",
                    packageName,
                    productId,
                    purchaseToken
            );

            log.info("🔍 Google 구독 검증 요청 URL: {}", url);
            log.info("🔍 productId: {}, purchaseToken: {}", productId, purchaseToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(getAccessToken());

            HttpEntity<String> entity = new HttpEntity<>(headers);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            log.info("✅ Google API 응답: {}", response.getBody());

            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(response.getBody());

            // paymentState: 0=결제대기, 1=결제완료, 2=무료체험
            // 테스트 구매는 paymentState가 없을 수도 있으므로 null 체크
            if (json.has("paymentState")) {
                int paymentState = json.get("paymentState").asInt();
                log.info("💳 paymentState: {}", paymentState);
                // 0(결제대기)도 테스트에선 허용, 실제 운영은 1만 허용
                return paymentState == 1 || paymentState == 0;
            }

            // paymentState 필드 없으면 구글이 응답 자체를 줬다는 건 유효한 것
            log.warn("⚠️ paymentState 필드 없음, 응답 전체: {}", response.getBody());
            return true;

        } catch (Exception e) {
            log.error("❌ Google 구독 검증 실패 - 예외: {}", e.getMessage(), e);
            return false;
        }
    }

    // Google API 호출 시 필요한 액세스 토큰 발급
    private String getAccessToken() throws Exception {
        InputStream stream;

        // Render 등 서버 환경: 환경변수에서 JSON 읽기
        String serviceAccountJson = System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON");
        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            log.info("🔑 서비스 계정: 환경변수에서 로드");
            stream = new java.io.ByteArrayInputStream(serviceAccountJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            // 로컬 개발환경: resources/service-account.json 파일 사용
            log.info("🔑 서비스 계정: ClassPath 파일에서 로드");
            stream = new ClassPathResource("service-account.json").getInputStream();
        }

        GoogleCredentials credentials = GoogleCredentials
                .fromStream(stream)
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