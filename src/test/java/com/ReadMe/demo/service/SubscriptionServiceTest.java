package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.dto.GooglePubSubMessage;
import com.ReadMe.demo.dto.GoogleSubscriptionPurchase;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.exception.SubscriptionOwnedByAnotherAccountException;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository repository;

    @Mock
    private GooglePlaySubscriptionClient googlePlayClient;

    private ObjectMapper objectMapper;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new SubscriptionService(repository, googlePlayClient, objectMapper);
    }

    @Test
    void subscribeUsesGoogleExpiryAndAcknowledgesPurchasedSubscription() {
        Instant expiry = Instant.now().plusSeconds(3600);
        GoogleSubscriptionPurchase purchase = purchase(
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_PENDING",
                expiry,
                true
        );
        SubscribeRequest request = request("token-a", "premium_monthly");

        when(googlePlayClient.getSubscription("token-a")).thenReturn(purchase);
        when(repository.findByPurchaseToken("token-a")).thenReturn(Optional.empty());
        when(repository.findByDeviceIdAndUserIsNullAndStatus("device-a", SubscriptionStatus.ACTIVE)).thenReturn(List.of());

        Map<String, Object> result = service.subscribe(request, null, "device-a");

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(googlePlayClient).acknowledge("premium_monthly", "token-a");
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(expiry);
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.get("isPremium")).isEqualTo(true);
    }

    @Test
    void subscribeRejectsPendingPurchaseWithoutGrantingPremium() {
        GoogleSubscriptionPurchase purchase = purchase(
                "SUBSCRIPTION_STATE_PENDING",
                "ACKNOWLEDGEMENT_STATE_PENDING",
                Instant.now().plusSeconds(3600),
                false
        );
        when(googlePlayClient.getSubscription("token-a")).thenReturn(purchase);

        assertThatThrownBy(() -> service.subscribe(request("token-a", "premium_monthly"), null, "device-a"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(googlePlayClient, never()).acknowledge("premium_monthly", "token-a");
        verify(repository, never()).save(any());
    }

    @Test
    void subscribeRejectsTokenOwnedByAnotherUser() {
        UserEntity owner = user(1L);
        UserEntity requester = user(2L);
        Subscription existing = new Subscription();
        existing.setUser(owner);
        existing.setPurchaseToken("token-a");

        when(googlePlayClient.getSubscription("token-a")).thenReturn(activePurchase());
        when(repository.findByPurchaseToken("token-a")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.subscribe(request("token-a", "premium_monthly"), requester, "device-a"))
                .isInstanceOf(SubscriptionOwnedByAnotherAccountException.class)
                .hasMessageContaining("다른 계정");

        verify(repository, never()).save(any());
    }

    @Test
    void guestCannotTakeOverSubscriptionLinkedToAccount() {
        Subscription accountPurchase = new Subscription();
        accountPurchase.setUser(user(1L));
        accountPurchase.setDeviceId("device-a");
        accountPurchase.setPurchaseToken("token-a");

        when(googlePlayClient.getSubscription("token-a")).thenReturn(activePurchase());
        when(repository.findByPurchaseToken("token-a")).thenReturn(Optional.of(accountPurchase));

        assertThatThrownBy(() -> service.subscribe(request("token-a", "premium_monthly"), null, "device-a"))
                .isInstanceOf(SubscriptionOwnedByAnotherAccountException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void guestPurchaseCanBeRestoredOnReinstalledDevice() {
        // 재설치로 deviceId 가 바뀌어도, 같은 Google 계정의 구매 토큰을 보내면 새 기기로 옮겨진다.
        Subscription guestPurchase = new Subscription();
        guestPurchase.setDeviceId("old-device");
        guestPurchase.setPurchaseToken("token-a");
        guestPurchase.setStatus(SubscriptionStatus.ACTIVE);

        when(googlePlayClient.getSubscription("token-a")).thenReturn(activePurchase());
        when(repository.findByPurchaseToken("token-a")).thenReturn(Optional.of(guestPurchase));
        when(repository.findByDeviceIdAndUserIsNullAndStatus("new-device", SubscriptionStatus.ACTIVE)).thenReturn(List.of());

        service.subscribe(request("token-a", "premium_monthly"), null, "new-device");

        assertThat(guestPurchase.getDeviceId()).isEqualTo("new-device");
        assertThat(guestPurchase.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        verify(repository).save(guestPurchase);
    }

    @Test
    void accountPurchaseDoesNotExpireOtherSubscriptionsOnSameDevice() {
        UserEntity user = user(2L);
        when(googlePlayClient.getSubscription("token-b")).thenReturn(activePurchase());
        when(repository.findByPurchaseToken("token-b")).thenReturn(Optional.empty());
        when(repository.findByUserAndStatus(user, SubscriptionStatus.ACTIVE)).thenReturn(List.of());

        service.subscribe(request("token-b", "premium_monthly"), user, "shared-device");

        verify(repository, never()).findByDeviceIdAndUserIsNullAndStatus(any(), any());
    }

    @Test
    void cancelledSubscriptionKeepsEntitlementUntilGoogleExpiry() {
        Instant expiry = Instant.now().plusSeconds(3600);
        when(googlePlayClient.getSubscription("token-a")).thenReturn(purchase(
                "SUBSCRIPTION_STATE_CANCELED",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                expiry,
                false
        ));
        when(repository.findByPurchaseToken("token-a")).thenReturn(Optional.empty());
        when(repository.findByDeviceIdAndUserIsNullAndStatus("device-a", SubscriptionStatus.ACTIVE)).thenReturn(List.of());

        service.subscribe(request("token-a", "premium_monthly"), null, "device-a");

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(captor.getValue().getAutoRenew()).isFalse();
    }

    @Test
    void rtdnReloadsAuthoritativeStateFromGoogle() throws Exception {
        Subscription existing = new Subscription();
        existing.setPurchaseToken("token-a");
        existing.setStatus(SubscriptionStatus.ACTIVE);
        when(repository.findByPurchaseToken("token-a")).thenReturn(Optional.of(existing));
        when(googlePlayClient.getSubscription("token-a")).thenReturn(purchase(
                "SUBSCRIPTION_STATE_EXPIRED",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                Instant.now().minusSeconds(60),
                false
        ));

        service.handleGoogleNotification(rtdn("token-a"));

        assertThat(existing.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        verify(repository).save(existing);
    }

    @Test
    void loggedInUserKeepsPremiumBoughtAsGuestOnSameDevice() {
        Subscription guestPurchase = new Subscription();
        guestPurchase.setDeviceId("device-a");
        guestPurchase.setStatus(SubscriptionStatus.ACTIVE);
        guestPurchase.setExpiresAt(Instant.now().plusSeconds(3600));

        UserEntity user = user(1L);
        when(repository.findTopByUserAndStatusOrderByExpiresAtDesc(user, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(repository.findTopByDeviceIdAndUserIsNullAndStatusOrderByExpiresAtDesc("device-a", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(guestPurchase));

        assertThat(service.isPremium(user, "device-a")).isTrue();
    }

    @Test
    void loggedInUserDoesNotInheritPremiumFromAnotherAccountOnSharedDevice() {
        UserEntity user = user(2L);
        when(repository.findTopByUserAndStatusOrderByExpiresAtDesc(user, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        // 같은 기기에 남의 계정 구독이 있어도 user IS NULL 조건 때문에 걸리지 않는다.
        when(repository.findTopByDeviceIdAndUserIsNullAndStatusOrderByExpiresAtDesc("device-a", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service.isPremium(user, "device-a")).isFalse();
    }

    @Test
    void guestDoesNotGetPremiumFromAccountSubscriptionOnSameDevice() {
        // 로그아웃한 비회원도 user IS NULL 조건으로만 조회하므로 계정 구독 권한이 따라오지 않는다.
        when(repository.findTopByDeviceIdAndUserIsNullAndStatusOrderByExpiresAtDesc("device-a", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service.isPremium(null, "device-a")).isFalse();
        verify(repository, never()).findTopByUserAndStatusOrderByExpiresAtDesc(any(), any());
    }

    @Test
    void loginLinksGuestSubscriptionToAccount() {
        Subscription guestPurchase = new Subscription();
        guestPurchase.setDeviceId("device-a");
        guestPurchase.setStatus(SubscriptionStatus.ACTIVE);
        UserEntity user = user(1L);
        when(repository.findByDeviceIdAndUserIsNull("device-a")).thenReturn(List.of(guestPurchase));

        int linked = service.linkDeviceSubscriptionsToUser("device-a", user);

        assertThat(linked).isEqualTo(1);
        assertThat(guestPurchase.getUser()).isSameAs(user);
        verify(repository).saveAll(List.of(guestPurchase));
    }

    @Test
    void withdrawalDetachesSubscriptionsFromAccountButKeepsPaymentRecord() {
        UserEntity user = user(1L);
        Subscription accountPurchase = new Subscription();
        accountPurchase.setUser(user);
        accountPurchase.setDeviceId("device-a");
        accountPurchase.setPurchaseToken("token-a");
        when(repository.findByUser(user)).thenReturn(List.of(accountPurchase));

        service.detachSubscriptionsFromUser(user);

        assertThat(accountPurchase.getUser()).isNull();
        assertThat(accountPurchase.getPurchaseToken()).isEqualTo("token-a");
        verify(repository).saveAll(List.of(accountPurchase));
        verify(repository, never()).delete(any());
    }

    private GoogleSubscriptionPurchase activePurchase() {
        return purchase(
                "SUBSCRIPTION_STATE_ACTIVE",
                "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
                Instant.now().plusSeconds(3600),
                true
        );
    }

    private GoogleSubscriptionPurchase purchase(
            String state,
            String acknowledgementState,
            Instant expiry,
            boolean autoRenew
    ) {
        return new GoogleSubscriptionPurchase(
                "premium_monthly",
                state,
                acknowledgementState,
                Instant.now().minusSeconds(60),
                expiry,
                autoRenew,
                null
        );
    }

    private SubscribeRequest request(String token, String productId) {
        SubscribeRequest request = new SubscribeRequest();
        request.setPlatform(Platform.ANDROID);
        request.setPlanType("monthly");
        request.setPurchaseToken(token);
        request.setProductId(productId);
        return request;
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        return user;
    }

    private GooglePubSubMessage rtdn(String purchaseToken) throws Exception {
        String data = Base64.getEncoder().encodeToString(
                ("{\"subscriptionNotification\":{\"purchaseToken\":\"" + purchaseToken + "\"}}").getBytes()
        );
        return objectMapper.readValue(
                "{\"message\":{\"data\":\"" + data + "\",\"messageId\":\"1\"}}",
                GooglePubSubMessage.class
        );
    }
}
