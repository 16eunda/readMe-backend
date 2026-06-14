package com.ReadMe.demo.dto;

import java.time.Instant;

public record GoogleSubscriptionPurchase(
        String productId,
        String subscriptionState,
        String acknowledgementState,
        Instant startedAt,
        Instant expiresAt,
        boolean autoRenew,
        String linkedPurchaseToken
) {
    public boolean isEntitled(Instant now) {
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return false;
        }

        return "SUBSCRIPTION_STATE_ACTIVE".equals(subscriptionState)
                || "SUBSCRIPTION_STATE_IN_GRACE_PERIOD".equals(subscriptionState)
                || "SUBSCRIPTION_STATE_CANCELED".equals(subscriptionState);
    }

    public boolean needsAcknowledgement() {
        return "ACKNOWLEDGEMENT_STATE_PENDING".equals(acknowledgementState);
    }
}
