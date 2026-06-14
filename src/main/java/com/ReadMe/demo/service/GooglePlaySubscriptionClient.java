package com.ReadMe.demo.service;

import com.ReadMe.demo.dto.GoogleSubscriptionPurchase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GooglePlaySubscriptionClient {

    private final ObjectMapper objectMapper;

    @Value("${google.play.package-name:com.readme.app}")
    private String packageName;

    public GoogleSubscriptionPurchase getSubscription(String purchaseToken) {
        if (purchaseToken == null || purchaseToken.isBlank()) {
            throw new IllegalArgumentException("Google Play 구매 토큰이 필요합니다.");
        }

        try {
            String url = String.format(
                    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/subscriptionsv2/tokens/%s",
                    packageName,
                    purchaseToken
            );

            ResponseEntity<String> response = new RestTemplate().exchange(
                    url,
                    HttpMethod.GET,
                    authorizedEntity(),
                    String.class
            );

            return parsePurchase(response.getBody());
        } catch (RestClientResponseException e) {
            logGoogleApiError("구독 조회", e);
            throw new IllegalStateException("Google Play 구독을 확인할 수 없습니다.", e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google Play 구독 조회 실패: {}", e.getMessage(), e);
            throw new IllegalStateException("Google Play 구독을 확인할 수 없습니다.", e);
        }
    }

    public void acknowledge(String productId, String purchaseToken) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("Google Play 상품 ID가 필요합니다.");
        }

        try {
            String url = String.format(
                    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/subscriptions/%s/tokens/%s:acknowledge",
                    packageName,
                    productId,
                    purchaseToken
            );
            new RestTemplate().exchange(url, HttpMethod.POST, authorizedEntity(), String.class);
        } catch (RestClientResponseException e) {
            logGoogleApiError("구독 승인", e);
            throw new IllegalStateException("Google Play 구독을 승인할 수 없습니다.", e);
        } catch (Exception e) {
            log.error("Google Play 구독 승인 실패: {}", e.getMessage(), e);
            throw new IllegalStateException("Google Play 구독을 승인할 수 없습니다.", e);
        }
    }

    GoogleSubscriptionPurchase parsePurchase(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode lineItems = root.path("lineItems");
        if (!lineItems.isArray() || lineItems.isEmpty()) {
            throw new IllegalArgumentException("Google Play 구독 상품 정보가 없습니다.");
        }

        JsonNode latestLineItem = null;
        Instant latestExpiry = null;
        for (JsonNode lineItem : lineItems) {
            Instant expiry = parseInstant(lineItem.path("expiryTime").asText(null));
            if (expiry != null && (latestExpiry == null || expiry.isAfter(latestExpiry))) {
                latestLineItem = lineItem;
                latestExpiry = expiry;
            }
        }

        if (latestLineItem == null) {
            throw new IllegalArgumentException("Google Play 구독 만료 정보가 없습니다.");
        }

        return new GoogleSubscriptionPurchase(
                latestLineItem.path("productId").asText(null),
                root.path("subscriptionState").asText(null),
                root.path("acknowledgementState").asText(null),
                parseInstant(root.path("startTime").asText(null)),
                latestExpiry,
                latestLineItem.path("autoRenewingPlan").path("autoRenewEnabled").asBoolean(false),
                root.path("linkedPurchaseToken").asText(null)
        );
    }

    private HttpEntity<String> authorizedEntity() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getAccessToken());
        return new HttpEntity<>(headers);
    }

    private String getAccessToken() throws Exception {
        InputStream stream;
        String serviceAccountJson = System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON");
        if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
            stream = new java.io.ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
        } else {
            stream = new ClassPathResource("service-account.json").getInputStream();
        }

        GoogleCredentials credentials = GoogleCredentials
                .fromStream(stream)
                .createScoped(List.of("https://www.googleapis.com/auth/androidpublisher"));
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private void logGoogleApiError(String operation, RestClientResponseException e) {
        log.error(
                "Google Play {} 실패. status={}, response={}",
                operation,
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
    }
}
