package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.enums.Platform;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubscribeRequest {
    private String planType;    // "monthly" | "yearly"
    private Platform platform;    // "ios" | "android"
    private String receipt;     // iOS 영수증
    private String purchaseToken; // Android
}
