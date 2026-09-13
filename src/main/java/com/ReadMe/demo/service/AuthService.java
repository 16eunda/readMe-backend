package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.LoginResponse;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.repository.AiAnalysisLogRepository;
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
    private AiAnalysisLogRepository aiAnalysisLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;  // BCrypt

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private SubscriptionService subscriptionService;

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
                fileRepository.linkDeviceToUser(deviceId, user.getId());
            }

            // 폴더 연결
            long folderCnt = folderRepository.countByDeviceIdAndUserIsNull(deviceId);
            if(folderCnt > 0) {
                folderRepository.linkDeviceToUser(deviceId, user.getId());
            }

            // 구독 연결
            // 게스트 상태에서 결제한 구독을 계정으로 승계한다.
            // 이게 없으면 결제한 사용자가 로그인하는 순간 프리미엄을 잃는다.
            subscriptionService.linkDeviceSubscriptionsToUser(deviceId, user);
        }

        // 2. 토큰 2개 발급
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId().toString());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId().toString());


        // 3. 응답
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId().toString());
        response.setUsername(user.getUsername());
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);  //  중요!

        return response;
    }

    // 토큰 재발급
    // accessToken 과 함께 refreshToken 도 새로 발급한다(슬라이딩 만료).
    // 앱을 계속 쓰는 사용자는 로그인이 유지되고, 30일 동안 한 번도 쓰지 않은 경우에만 다시 로그인한다.
    // 이전 refreshToken 도 자기 만료일까지는 유효하므로, 새 토큰 저장에 실패한 앱이 로그아웃되지는 않는다.
    public Map<String, String> refreshToken(String authHeader) {
        // 1. Authorization 헤더에서 refreshToken 추출
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("토큰이 없습니다");
        }

        String refreshToken = authHeader.substring(7); // "Bearer " 제거

        // 2. refreshToken 유효성 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("토큰이 만료되었습니다");
        }

        // 3. accessToken 으로는 재발급할 수 없다.
        //    (예전에는 두 토큰이 구분되지 않아 탈취한 accessToken 으로 무기한 연장이 가능했다)
        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new IllegalArgumentException("refreshToken이 아닙니다");
        }

        // 4. refreshToken에서 사용자 정보 추출
        String userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

        // 5. DB에서 사용자 존재 확인
        //    탈퇴한 사용자도 @Where 로 조회되지 않는다. 여기서 500 을 주면 앱이 로그아웃하지 못하고 갇힌다.
        userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new IllegalArgumentException("탈퇴했거나 존재하지 않는 사용자입니다"));

        // 6. 새 토큰 발급
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", jwtTokenProvider.generateAccessToken(userId));
        tokens.put("refreshToken", jwtTokenProvider.generateRefreshToken(userId));
        return tokens;
    }

    // 회원 탈퇴
    // - 계정에 딸린 개인 데이터(책 기록·읽기 기록·폴더·AI 사용 기록)는 즉시 삭제한다. (Google Play 계정 삭제 정책)
    // - 구독(결제 기록)은 지우지 않고 계정 연결만 끊는다. Google Play 구독 해지는 사용자가 스토어에서 직접 한다.
    // - 계정 행은 아이디·비밀번호·이메일을 비운 채 탈퇴 시각만 남긴다. 아이디를 비우므로 같은 아이디로 다시 가입할 수 있다.
    @Transactional
    public void withdraw(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails)) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }

        // 인증 필터가 넘겨준 엔티티는 이 트랜잭션 밖에서 조회된 준영속 상태라 값을 바꿔도 저장되지 않는다.
        // (그래서 예전에는 탈퇴 API 가 200 을 주고도 실제로는 탈퇴되지 않았다.) 이 트랜잭션에서 다시 조회한다.
        // 이미 탈퇴한 사용자는 @Where 때문에 조회되지 않는다.
        Long userId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("이미 탈퇴했거나 존재하지 않는 사용자입니다."));

        fileRepository.deleteByUser(user);
        folderRepository.deleteByUser(user);
        aiAnalysisLogRepository.deleteByUser(user);
        subscriptionService.detachSubscriptionsFromUser(user);

        // soft delete + 개인정보 제거
        user.setUsername(null);
        user.setPassword(null);
        user.setEmail(null);
        user.setDeletedAt(LocalDateTime.now());
    }
}
