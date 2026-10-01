package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.dto.GoogleSubscriptionPurchase;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.ReadMe.demo.service.GooglePlaySubscriptionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 앱은 시작할 때마다 Google Play 에 살아 있는 구독을 모두 조회해서 /subscriptions/subscribe 로 다시 보낸다(구매 복원).
 * 그래서 서버는
 * ① 같은 구매 토큰이 계속 와도 한 건으로 유지하고
 * ② 다른 계정에 연결된 구독이면 409 SUBSCRIPTION_OWNED_BY_OTHER_ACCOUNT 로 거절해야 한다.
 * 앱과 똑같이 실제 HTTP 로 요청해 확인한다. Google Play API 만 가짜로 대체한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:subscription-restore;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
)
class SubscriptionRestoreIntegrationTest {

    private static final String PRODUCT_ID = "monthly_2900";

    @LocalServerPort
    private int port;

    @MockitoBean
    private GooglePlaySubscriptionClient googlePlayClient;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void sameTokenSentOnEveryAppLaunchStaysOneSubscription() throws Exception {
        String device = "phone-" + UUID.randomUUID();
        String accessToken = signupAndLogin("user-" + UUID.randomUUID(), device);
        String token = "token-" + UUID.randomUUID();
        // 첫 등록 때는 아직 승인 전, 그 뒤로는 승인된 상태로 조회된다.
        when(googlePlayClient.getSubscription(token)).thenReturn(
                purchase("ACKNOWLEDGEMENT_STATE_PENDING"),
                purchase("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED")
        );

        // 결제 직후 등록 + 앱을 두 번 더 켰을 때의 복원
        for (int launch = 0; launch < 3; launch++) {
            assertThat(subscribe(token, accessToken, device).statusCode()).isEqualTo(200);
        }

        long rows = subscriptionRepository.findAll().stream()
                .filter(subscription -> token.equals(subscription.getPurchaseToken()))
                .count();
        assertThat(rows).isEqualTo(1);
        verify(googlePlayClient, times(1)).acknowledge(PRODUCT_ID, token);
        assertThat(isPremium(accessToken, device)).isTrue();
    }

    @Test
    void anotherAccountOnSamePhoneCannotTakeOverSubscription() throws Exception {
        String sharedPhone = "phone-" + UUID.randomUUID();
        String token = "token-" + UUID.randomUUID();
        when(googlePlayClient.getSubscription(token)).thenReturn(purchase("ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"));

        // A 계정으로 결제
        String accountA = signupAndLogin("a-" + UUID.randomUUID(), sharedPhone);
        assertThat(subscribe(token, accountA, sharedPhone).statusCode()).isEqualTo(200);
        Long ownerId = subscriptionRepository.findByPurchaseToken(token).orElseThrow().getUser().getId();

        // A 로그아웃 → 같은 폰에서 B 로그인 → 앱 시작 시 복원이 A 의 구독을 B 로 등록하려 함
        String accountB = signupAndLogin("b-" + UUID.randomUUID(), sharedPhone);
        HttpResponse<String> asB = subscribe(token, accountB, sharedPhone);
        assertThat(asB.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(asB.body()).path("code").asText())
                .isEqualTo("SUBSCRIPTION_OWNED_BY_OTHER_ACCOUNT");

        // 로그아웃한 비회원 상태로 앱을 켜도 마찬가지
        HttpResponse<String> asGuest = subscribe(token, null, sharedPhone);
        assertThat(asGuest.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(asGuest.body()).path("code").asText())
                .isEqualTo("SUBSCRIPTION_OWNED_BY_OTHER_ACCOUNT");

        // 권한은 A 에게만 있고, 구독 주인도 그대로 A 다.
        assertThat(isPremium(accountB, sharedPhone)).isFalse();
        assertThat(isPremium(null, sharedPhone)).isFalse();
        assertThat(isPremium(accountA, sharedPhone)).isTrue();
        Subscription kept = subscriptionRepository.findByPurchaseToken(token).orElseThrow();
        assertThat(kept.getUser().getId()).isEqualTo(ownerId);
    }

    // ── helpers ──

    private GoogleSubscriptionPurchase purchase(String acknowledgementState) {
        return new GoogleSubscriptionPurchase(
                PRODUCT_ID,
                "SUBSCRIPTION_STATE_ACTIVE",
                acknowledgementState,
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(30L * 24 * 3600),
                true,
                null
        );
    }

    private String signupAndLogin(String username, String deviceId) throws Exception {
        String credentials = "{\"username\":\"" + username + "\",\"password\":\"pw\"";
        assertThat(postJson("/auth/signup", credentials + "}", null, null).statusCode()).isEqualTo(200);
        HttpResponse<String> login = postJson(
                "/auth/login", credentials + ",\"deviceId\":\"" + deviceId + "\"}", null, null
        );
        assertThat(login.statusCode()).isEqualTo(200);
        return objectMapper.readTree(login.body()).path("accessToken").asText();
    }

    private HttpResponse<String> subscribe(String purchaseToken, String accessToken, String deviceId) throws Exception {
        // 앱(restorePurchases)이 보내는 본문과 같다.
        String body = "{\"purchaseToken\":\"" + purchaseToken + "\",\"productId\":\"" + PRODUCT_ID
                + "\",\"planType\":\"monthly\",\"platform\":\"ANDROID\"}";
        return postJson("/subscriptions/subscribe", body, accessToken, deviceId);
    }

    private boolean isPremium(String accessToken, String deviceId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/subscriptions/status"))
                .header("X-Device-Id", deviceId)
                .GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        return body.path("isPremium").asBoolean();
    }

    private HttpResponse<String> postJson(String path, String json, String accessToken, String deviceId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        if (deviceId != null) {
            builder.header("X-Device-Id", deviceId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
