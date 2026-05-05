package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.LoginResponse;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.FolderRepository;
import com.ReadMe.demo.repository.UserRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.security.JwtTokenProvider;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;  // BCrypt

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // 회원가입
    @Transactional
    public void signup(String username, String password) {
        // 중복 체크
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 존재하는 아이디입니다");
        }

        // 사용자 생성
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setCreatedAt(LocalDateTime.now());

        userRepository.save(user);
    }

    // 로그인
    @Transactional
    public LoginResponse login(String username, String password, String deviceId) {
        // 사용자 인증
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 틀렸습니다"));

        // 패스워드 검증
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("아이디 또는 비밀번호가 틀렸습니다");
        }

        // deviceId → userId 연결 (파일 + 폴더)
        if (deviceId != null && !deviceId.isEmpty()) {

            // 파일 연결
            long fileCnt = fileRepository.countByDeviceIdAndUserIsNull(deviceId);
            if(fileCnt > 0) {
                int updated = fileRepository.linkDeviceToUser(deviceId, user.getId());
                System.out.println("✅ " + updated + "개 파일이 사용자와 연결되었습니다");
            } else {
                System.out.println("ℹ️ 연결할 파일 없음");
            }

            // 폴더 연결
            long folderCnt = folderRepository.countByDeviceIdAndUserIsNull(deviceId);
            if(folderCnt > 0) {
                int updated = folderRepository.linkDeviceToUser(deviceId, user.getId());
                System.out.println("✅ " + updated + "개 폴더가 사용자와 연결되었습니다");
            } else {
                System.out.println("ℹ️ 연결할 폴더 없음");
            }
        }

        // 2. 토큰 2개 발급
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId().toString());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId().toString());

        // 만료시간 찍기
        System.out.println("AccessToken 만료시간: " + jwtTokenProvider.getExpirationDateFromToken(accessToken));
        System.out.println("RefreshToken 만료시간: " + jwtTokenProvider.getExpirationDateFromToken(refreshToken));


        // 3. 응답
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId().toString());
        response.setUsername(user.getUsername());
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);  //  중요!

        return response;
    }

    // 토큰 재발급
    public String refreshToken(String authHeader) {
        // 1. Authorization 헤더에서 refreshToken 추출
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("토큰이 없습니다");
        }

        String refreshToken = authHeader.substring(7); // "Bearer " 제거

        // 2. refreshToken 유효성 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("토큰이 만료되었습니다");
        }

        // 3. refreshToken에서 사용자 정보 추출
        String userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

        // 4. DB에서 사용자 존재 확인 (선택사항)
        UserEntity user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다"));

        // 5. 새로운 accessToken 발급
        String newAccessToken = jwtTokenProvider.generateAccessToken(userId);

        return newAccessToken;
    }

    // 회원 탈퇴
    @Transactional
    public void withdraw(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails)) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }

        UserEntity user = ((CustomUserDetails) authentication.getPrincipal()).getUser();

        // 이미 탈퇴한 경우 방지
        if (user.getDeletedAt() != null) {
            throw new RuntimeException("이미 탈퇴한 사용자입니다.");
        }

        // soft delete
        user.setDeletedAt(LocalDateTime.now());
    }
}