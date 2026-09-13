package com.ReadMe.demo.exception;

/**
 * 구매 토큰이 이미 다른 앱 계정에 연결돼 있을 때.
 * 앱은 이 코드를 받으면 "구독한 계정으로 로그인하세요"를 안내한다.
 */
public class SubscriptionOwnedByAnotherAccountException extends RuntimeException {

    public SubscriptionOwnedByAnotherAccountException(String message) {
        super(message);
    }
}
