package com.ReadMe.demo.service;

import com.ReadMe.demo.dto.GoogleSubscriptionPurchase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GooglePlaySubscriptionClientTest {

    private final GooglePlaySubscriptionClient client =
            new GooglePlaySubscriptionClient(new ObjectMapper(), new org.springframework.web.client.RestTemplate());

    @Test
    void parsesLatestLineItemFromSubscriptionsV2Response() throws Exception {
        String response = """
                {
                  "startTime": "2026-06-14T00:00:00Z",
                  "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
                  "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
                  "lineItems": [
                    {
                      "productId": "old_product",
                      "expiryTime": "2026-06-15T00:00:00Z"
                    },
                    {
                      "productId": "premium_monthly",
                      "expiryTime": "2026-07-14T00:00:00Z",
                      "autoRenewingPlan": {
                        "autoRenewEnabled": true
                      }
                    }
                  ]
                }
                """;

        GoogleSubscriptionPurchase purchase = client.parsePurchase(response);

        assertThat(purchase.productId()).isEqualTo("premium_monthly");
        assertThat(purchase.expiresAt()).isEqualTo(Instant.parse("2026-07-14T00:00:00Z"));
        assertThat(purchase.autoRenew()).isTrue();
        assertThat(purchase.needsAcknowledgement()).isTrue();
    }
}
