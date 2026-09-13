package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.SubscribeRequest;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionControllerTest {

    @Test
    void subscribeUsesAuthenticatedUserWithoutLookingUpNullableEmail() {
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        SubscriptionController controller = new SubscriptionController(subscriptionService);
        Authentication authentication = mock(Authentication.class);
        SubscribeRequest request = new SubscribeRequest();
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setEmail(null);

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(new CustomUserDetails(user));
        when(subscriptionService.subscribe(request, user, "device-a"))
                .thenReturn(Map.of("isPremium", true));

        controller.subscribe(request, authentication, "device-a");

        verify(subscriptionService).subscribe(request, user, "device-a");
    }
}
