package com.ReadMe.demo.controller;


import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.LoginRequest;
import com.ReadMe.demo.dto.LoginResponse;
import com.ReadMe.demo.dto.SignupRequest;
import com.ReadMe.demo.exception.LoginBlockedException;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    // 회원가입
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        try {
            authService.signup(request.getUsername(), request.getPassword());
            return ResponseEntity.ok("회원가입 성공");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 로그인
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(
                    request.getUsername(),
                    request.getPassword(),
                    request.getDeviceId()
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        } catch (LoginBlockedException e) {
            // 앱은 실패 응답 본문을 그대로 알림창에 띄우므로 401 과 같이 문구만 보낸다.
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                    .body(e.getMessage());
        }
    }

    // 로그아웃 → 이 기기의 로그인 세션을 끝낸다. 재발급과 같이 Authorization 헤더로 refreshToken 을 받는다.
    // 앱은 결과와 상관없이 기기의 토큰을 지우므로 항상 200 이다. (이미 로그아웃된 토큰, 토큰 없음 포함)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.logout(authHeader);
        return ResponseEntity.ok().build();
    }

    // 토큰 재발급 → { accessToken, refreshToken }
    // 헤더가 없어도 400 이 아니라 401 로 응답해야 앱이 세션 종료로 판단한다.
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken (@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            return ResponseEntity.ok(authService.refreshToken(authHeader));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        } catch (Exception e) {
            // 5xx 면 앱은 로그인을 유지한다. 내부 메시지(DB 주소 등)는 응답에 싣지 않고 로그에만 남긴다.
            log.error("토큰 재발급 중 서버 오류", e);
            return ResponseEntity.status(500).body("서버 오류");
        }
    }

    // 내 정보 조회. 앱이 저장된 accessToken 이 아직 유효한지 확인할 때 쓴다.
    // 앱이 /auth/user/me 로 호출하고 있어 두 주소를 모두 받는다. 앱을 /auth/users/me 로 맞춘 뒤 /user/me 는 제거한다.
    @GetMapping({"/users/me", "/user/me"})
    public ResponseEntity<Map<String, String>> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails details)) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }

        UserEntity user = details.getUser();
        return ResponseEntity.ok(Map.of(
                "userId", String.valueOf(user.getId()),
                "username", user.getUsername()
        ));
    }

    // 회원 탈퇴 (두 주소를 받는 이유는 위와 같다)
    @DeleteMapping({"/users/me", "/user/me"})
    public ResponseEntity<Void> withdraw(Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedException("회원 탈퇴 : 인증 정보 없음");
        }

        authService.withdraw(authentication);
        return ResponseEntity.ok().build();
    }
}
