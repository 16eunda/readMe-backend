package com.ReadMe.demo.controller;


import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.LoginRequest;
import com.ReadMe.demo.dto.LoginResponse;
import com.ReadMe.demo.dto.SignupRequest;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

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
        }
    }

    // 토큰 재발급
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken (@RequestHeader("Authorization") String authHeader) {
        try {
            String newAccessToken = authService.refreshToken(authHeader);
            Map<String, String> response = new HashMap<>();
            response.put("accessToken", newAccessToken);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("서버 오류: " + e.getMessage());
        }
    }

    // 회원 탈퇴
    @DeleteMapping("/users/me")
    public ResponseEntity<Void> withdraw(Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedException("회원 탈퇴 : 인증 정보 없음");
        }

        authService.withdraw(authentication);
        return ResponseEntity.ok().build();
    }
}