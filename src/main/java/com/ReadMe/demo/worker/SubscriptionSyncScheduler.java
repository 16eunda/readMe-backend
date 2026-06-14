package com.ReadMe.demo.worker;

import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.ReadMe.demo.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionSyncScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "${google.play.sync-cron:0 15 3 * * *}")
    public void synchronizeActiveGoogleSubscriptions() {
        subscriptionRepository.findByPurchaseTokenIsNotNullAndStatus(SubscriptionStatus.ACTIVE)
                .forEach(subscription -> {
                    try {
                        subscriptionService.synchronizeGoogleSubscription(subscription.getId());
                    } catch (RuntimeException e) {
                        log.error("Google Play 구독 동기화 실패. subscriptionId={}", subscription.getId(), e);
                    }
                });
    }
}
