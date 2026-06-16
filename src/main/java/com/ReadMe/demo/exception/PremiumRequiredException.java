package com.ReadMe.demo.exception;

public class PremiumRequiredException extends RuntimeException {
    public PremiumRequiredException() {
        super("프리미엄 구독이 필요한 기능입니다.");
    }
}
