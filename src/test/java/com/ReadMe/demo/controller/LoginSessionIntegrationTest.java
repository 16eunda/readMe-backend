package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.RefreshSession;
import com.ReadMe.demo.repository.RefreshSessionRepository;
import com.ReadMe.demo.security.JwtTokenProvider;
import com.ReadMe.demo.service.GeminiService;
import com.ReadMe.demo.support.TestApi;
import com.ReadMe.demo.worker.AnalysisWorker;
import com.ReadMe.demo.worker.RefreshSessionCleanupScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 세션(로그아웃 시 refreshToken 무효화)과 로그인 시도 제한.
 * 앱의 로그아웃: 서버에 POST /auth/logout(refreshToken)을 보내고, 결과와 상관없이 기기의 토큰을 지운다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:login-session;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
)
class LoginSessionIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RefreshSessionRepository refreshSessionRepository;

    @Autowired
    private RefreshSessionCleanupScheduler cleanupScheduler;

    @MockitoBean
    private AnalysisWorker analysisWorker;

    @MockitoBean
    private GeminiService geminiService;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, objectMapper);
    }

    // 폰에서 로그아웃하면 폰의 refreshToken 은 (재발급받은 새 토큰까지) 모두 막히고, 태블릿 로그인은 그대로다.
    @Test
    void logoutEndsOnlyThisDevicesSession() throws Exception {
        String username = signup();
        JsonNode phone = login(username, "pw");
        JsonNode tablet = login(username, "pw");
        String phoneRefresh = phone.path("refreshToken").asText();

        HttpResponse<String> renewed = refresh(phoneRefresh);
        assertThat(renewed.statusCode()).isEqualTo(200);
        String phoneRenewedRefresh = api.json(renewed).path("refreshToken").asText();

        assertThat(logout(phoneRefresh).statusCode()).isEqualTo(200);

        assertThat(refresh(phoneRefresh).statusCode()).isEqualTo(401);
        assertThat(refresh(phoneRenewedRefresh).statusCode()).isEqualTo(401);
        assertThat(refresh(tablet.path("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    // 앱은 오프라인 재시도·연타로 같은 로그아웃을 여러 번 보낼 수 있고, 토큰이 이미 만료됐을 수도 있다.
    @Test
    void logoutIsSafeToRepeatAndIgnoresUnusableTokens() throws Exception {
        String username = signup();
        JsonNode session = login(username, "pw");
        String refreshToken = session.path("refreshToken").asText();

        // accessToken 이나 엉뚱한 값으로는 아무 세션도 지우지 않는다.
        assertThat(logout(session.path("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(logout("not-a-jwt").statusCode()).isEqualTo(200);
        assertThat(logout(null).statusCode()).isEqualTo(200);
        assertThat(refresh(refreshToken).statusCode()).isEqualTo(200);

        assertThat(logout(refreshToken).statusCode()).isEqualTo(200);
        assertThat(logout(refreshToken).statusCode()).isEqualTo(200);
        assertThat(refresh(refreshToken).statusCode()).isEqualTo(401);
    }

    // 세션 도입 전에 발급된 refreshToken(세션 id 없음)은 받지 않는다. 그때 로그인한 사용자는 한 번 다시 로그인한다.
    @Test
    void refreshTokenIssuedBeforeSessionsIsRejected() throws Exception {
        String username = signup();
        String userId = login(username, "pw").path("userId").asText();
        Date now = new Date();
        String legacy = Jwts.builder()
                .setSubject(userId)
                .claim("typ", "refresh")
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + Duration.ofDays(30).toMillis()))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS512)
                .compact();

        assertThat(refresh(legacy).statusCode()).isEqualTo(401);
    }

    // 앱을 쓰는 동안(재발급할 때마다) 세션 만료가 30일 뒤로 늦춰진다.
    @Test
    void refreshKeepsSessionAliveForAnother30Days() throws Exception {
        String refreshToken = login(signup(), "pw").path("refreshToken").asText();
        RefreshSession session = sessionOf(refreshToken);
        session.setExpiresAt(Instant.now().plus(Duration.ofDays(1)));
        refreshSessionRepository.save(session);

        assertThat(refresh(refreshToken).statusCode()).isEqualTo(200);

        assertThat(sessionOf(refreshToken).getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    // 30일 동안 쓰지 않은 세션은 재발급되지 않고, 하루 한 번 정리된다.
    @Test
    void expiredSessionIsRejectedAndCleanedUp() throws Exception {
        String refreshToken = login(signup(), "pw").path("refreshToken").asText();
        RefreshSession session = sessionOf(refreshToken);
        session.setExpiresAt(Instant.now().minusSeconds(1));
        refreshSessionRepository.save(session);

        assertThat(refresh(refreshToken).statusCode()).isEqualTo(401);

        cleanupScheduler.deleteExpiredSessions();
        assertThat(refreshSessionRepository.findById(session.getId())).isEmpty();
    }

    // 탈퇴하면 모든 기기의 로그인이 끝난다.
    @Test
    void withdrawalEndsSessionsOnAllDevices() throws Exception {
        String username = signup();
        JsonNode phone = login(username, "pw");
        JsonNode tablet = login(username, "pw");

        assertThat(api.send("DELETE", "/auth/users/me", null, phone.path("accessToken").asText(), null).statusCode())
                .isEqualTo(200);

        assertThat(refreshSessionRepository.findById(sessionIdOf(phone))).isEmpty();
        assertThat(refreshSessionRepository.findById(sessionIdOf(tablet))).isEmpty();
    }

    // 같은 아이디로 5번 연속 틀리면, 맞는 비밀번호여도 잠시 막힌다. 다른 아이디는 영향이 없다.
    @Test
    void repeatedWrongPasswordsBlockOnlyThatUsername() throws Exception {
        String target = signup();
        String other = signup();

        for (int i = 0; i < 5; i++) {
            assertThat(loginResponse(target, "wrong").statusCode()).isEqualTo(401);
        }

        HttpResponse<String> blocked = loginResponse(target, "pw");
        assertThat(blocked.statusCode()).isEqualTo(429);
        // 앱은 실패 응답 본문을 그대로 알림창에 띄운다.
        assertThat(blocked.body()).contains("로그인 시도가 너무 많습니다", "분 후 다시 시도해 주세요");
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();

        assertThat(loginResponse(other, "pw").statusCode()).isEqualTo(200);
        // 없는 아이디도 똑같이 센다.
        String ghost = "ghost-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertThat(loginResponse(ghost, "pw").statusCode()).isEqualTo(401);
        }
        assertThat(loginResponse(ghost, "pw").statusCode()).isEqualTo(429);
    }

    // 몇 번 틀려도 성공하면 처음부터 다시 센다.
    @Test
    void successfulLoginResetsFailureCount() throws Exception {
        String username = signup();
        for (int i = 0; i < 4; i++) {
            assertThat(loginResponse(username, "wrong").statusCode()).isEqualTo(401);
        }
        assertThat(loginResponse(username, "pw").statusCode()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            assertThat(loginResponse(username, "wrong").statusCode()).isEqualTo(401);
        }
        assertThat(loginResponse(username, "pw").statusCode()).isEqualTo(200);
    }

    private String signup() throws Exception {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(api.send("POST", "/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"pw\"}", null, null).statusCode()).isEqualTo(200);
        return username;
    }

    private JsonNode login(String username, String password) throws Exception {
        HttpResponse<String> response = loginResponse(username, password);
        assertThat(response.statusCode()).isEqualTo(200);
        return api.json(response);
    }

    private HttpResponse<String> loginResponse(String username, String password) throws Exception {
        return api.send("POST", "/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"deviceId\":\"device-"
                        + UUID.randomUUID() + "\"}", null, null);
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return api.send("POST", "/auth/refresh", null, refreshToken, null);
    }

    private HttpResponse<String> logout(String refreshToken) throws Exception {
        return api.send("POST", "/auth/logout", null, refreshToken, null);
    }

    private RefreshSession sessionOf(String refreshToken) {
        return refreshSessionRepository.findById(jwtTokenProvider.getSessionIdFromToken(refreshToken)).orElseThrow();
    }

    private Long sessionIdOf(JsonNode login) {
        return jwtTokenProvider.getSessionIdFromToken(login.path("refreshToken").asText());
    }
}
