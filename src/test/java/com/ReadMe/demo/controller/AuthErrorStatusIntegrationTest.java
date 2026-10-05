package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.FolderRepository;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.ReadMe.demo.repository.UserRepository;
import com.ReadMe.demo.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱은 상태 코드로 세션/구독 상태를 판단한다.
 * - 401/403 → 로그아웃, 5xx → "일시 장애"로 보고 로그인 유지
 * - 구독 조회 200 → 그 값을 믿음, 5xx → 이전 상태 유지
 * 그래서 인증 실패가 500 이나 "비회원 200" 으로 새어 나가면 앱이 잘못된 상태에 갇힌다.
 * 필터와 에러 디스패치까지 거쳐야 재현되므로 실제 서버를 띄워 검증한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 다른 @SpringBootTest 컨텍스트와 인메모리 DB를 공유하지 않도록 분리한다.
                "spring.datasource.url=jdbc:h2:mem:auth-status;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "google.play.webhook-token=test-webhook-token"
        }
)
class AuthErrorStatusIntegrationTest {

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private FileReadLogRepository fileReadLogRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── ① /auth/refresh 는 토큰 거부를 반드시 401 로 알려야 한다 ──

    @Test
    void refreshWithExpiredTokenReturns401() throws Exception {
        String expired = expiredToken(createUser().getId().toString(), "refresh");

        assertThat(post("/auth/refresh", expired).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshWithMalformedTokenReturns401() throws Exception {
        assertThat(post("/auth/refresh", "not-a-jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void refreshWithoutAuthorizationHeaderReturns401() throws Exception {
        assertThat(post("/auth/refresh", null).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshWithAccessTokenReturns401() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(createUser().getId().toString());

        assertThat(post("/auth/refresh", accessToken).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshForUnknownUserReturns401() throws Exception {
        String refreshToken = jwtTokenProvider.generateRefreshToken("987654321", 1L);

        assertThat(post("/auth/refresh", refreshToken).statusCode()).isEqualTo(401);
    }

    @Test
    void refreshRenewsRefreshTokenSoActiveUsersStayLoggedIn() throws Exception {
        String refreshToken = loginAndGetRefreshToken();

        HttpResponse<String> response = post("/auth/refresh", refreshToken);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("accessToken").asText()).isNotBlank();
        String renewed = body.path("refreshToken").asText();
        assertThat(renewed).isNotBlank();
        assertThat(jwtTokenProvider.getExpirationDateFromToken(renewed))
                .isAfterOrEqualTo(jwtTokenProvider.getExpirationDateFromToken(refreshToken));
        assertThat(post("/auth/refresh", renewed).statusCode()).isEqualTo(200);
        // 새 refreshToken 저장에 실패했거나 아직 저장하지 않는 앱도 기존 토큰으로 계속 재발급할 수 있어야 한다.
        assertThat(post("/auth/refresh", refreshToken).statusCode()).isEqualTo(200);
    }

    // ── 회원 탈퇴 ──

    @Test
    void withdrawalDeletesPersonalDataKeepsPaymentRecordAndFreesUsername() throws Exception {
        String username = "withdraw-" + UUID.randomUUID();
        String deviceId = "device-" + UUID.randomUUID();
        String credentials = "{\"username\":\"" + username + "\",\"password\":\"pw\"}";
        assertThat(postJson("/auth/signup", credentials).statusCode()).isEqualTo(200);
        JsonNode login = objectMapper.readTree(postJson("/auth/login", credentials).body());
        String accessToken = login.path("accessToken").asText();
        String refreshToken = login.path("refreshToken").asText();

        // 책 기록 + 읽기 기록 (계정 소유)
        HttpResponse<String> savedFile = send(
                HttpRequest.newBuilder(uri("/files"))
                        .header("Content-Type", "application/json")
                        .header("X-Device-Id", deviceId)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"title\":\"book.txt\",\"path\":\"root\",\"date\":\"2026-09-13T00:00:00Z\"}"
                        )),
                accessToken
        );
        assertThat(savedFile.statusCode()).isEqualTo(200);
        long fileId = objectMapper.readTree(savedFile.body()).path("id").asLong();
        HttpResponse<String> progress = send(
                HttpRequest.newBuilder(uri("/files/" + fileId + "/progress"))
                        .header("Content-Type", "application/json")
                        .header("X-Device-Id", deviceId)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                "{\"progress\":0.5,\"recordReadLog\":true}"
                        )),
                accessToken
        );
        assertThat(progress.statusCode()).isEqualTo(200);
        assertThat(fileReadLogRepository.count()).isPositive();

        // 결제 기록 (계정에 연결된 구독)
        String purchaseToken = "token-" + UUID.randomUUID();
        Subscription subscription = new Subscription();
        subscription.setUser(userRepository.findByUsername(username).orElseThrow());
        subscription.setDeviceId(deviceId);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setExpiresAt(Instant.now().plusSeconds(3600));
        subscription.setPurchaseToken(purchaseToken);
        subscriptionRepository.save(subscription);

        HttpResponse<String> me = get("/auth/users/me", accessToken, deviceId);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(me.body()).path("username").asText()).isEqualTo(username);

        // 현재 앱은 /auth/user/me 로 탈퇴를 요청한다.
        assertThat(send(HttpRequest.newBuilder(uri("/auth/user/me")).DELETE(), accessToken).statusCode())
                .isEqualTo(200);

        // 토큰은 더 이상 쓸 수 없다.
        assertThat(post("/auth/refresh", refreshToken).statusCode()).isEqualTo(401);
        assertThat(get("/files", accessToken, deviceId).statusCode()).isEqualTo(401);

        // 개인 데이터는 삭제, 결제 기록은 계정 연결만 끊고 보존
        assertThat(fileRepository.findAll()).noneMatch(file -> deviceId.equals(file.getDeviceId()));
        assertThat(fileReadLogRepository.count()).isZero();
        Subscription kept = subscriptionRepository.findByPurchaseToken(purchaseToken).orElseThrow();
        assertThat(kept.getUser()).isNull();
        assertThat(kept.getDeviceId()).isEqualTo(deviceId);

        // 같은 아이디로 다시 가입할 수 있다.
        assertThat(postJson("/auth/signup", credentials).statusCode()).isEqualTo(200);
        assertThat(postJson("/auth/login", credentials).statusCode()).isEqualTo(200);
    }

    // ── 만료된 accessToken 이 조용히 "비회원 요청"으로 처리되면 안 된다 ──

    @Test
    void expiredAccessTokenIsRejectedInsteadOfFallingBackToGuest() throws Exception {
        String expired = expiredToken(createUser().getId().toString(), "access");

        assertThat(get("/subscriptions/status", expired, "device-a").statusCode()).isEqualTo(401);
        assertThat(get("/files", expired, "device-a").statusCode()).isEqualTo(401);
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        String refreshToken = jwtTokenProvider.generateRefreshToken(createUser().getId().toString(), 1L);

        assertThat(get("/files", refreshToken, "device-a").statusCode()).isEqualTo(401);
    }

    @Test
    void googleWebhookIsNotRejectedByForeignAuthorizationHeader() throws Exception {
        // Pub/Sub push 인증을 켜면 Google 이 서명한 OIDC 토큰이 Authorization 헤더로 온다.
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/subscriptions/webhook/google?token=test-webhook-token"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")),
                "google-signed-oidc-token"
        );

        assertThat(response.statusCode()).isNotEqualTo(401);
    }

    // ── ② 구독이 없으면 200 + isPremium:false ──

    @Test
    void subscriptionStatusWithoutSubscriptionReturnsFalse() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(createUser().getId().toString());

        HttpResponse<String> member = get("/subscriptions/status", accessToken, "device-none");
        HttpResponse<String> guest = get("/subscriptions/status", null, "device-none");

        assertThat(member.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(member.body()).path("isPremium").asBoolean(true)).isFalse();
        assertThat(guest.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(guest.body()).path("isPremium").asBoolean(true)).isFalse();
    }

    // ── 그 밖의 상태 코드 / 데이터 격리 ──

    @Test
    void frameworkErrorsKeepTheirOwnStatusInsteadOf403() throws Exception {
        // X-Device-Id 필수 헤더 누락 → 400 이어야 한다. (/error 가 막혀 있으면 403 으로 바뀌어
        // 앱이 "권한 거부/프리미엄 필요"로 오해한다.)
        HttpResponse<String> response = get("/files/check?title=a&path=root", null, null);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void guestWithoutDeviceIdCannotListFoldersOwnedByOthers() throws Exception {
        FolderEntity folder = new FolderEntity();
        folder.setName("someone-else-" + UUID.randomUUID());
        folder.setPath("root");
        folder.setUser(createUser());
        folder.setDeviceId(null);
        folderRepository.save(folder);

        HttpResponse<String> response = get("/folders", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(folder.getName());
    }

    // ── helpers ──

    private UserEntity createUser() {
        UserEntity user = new UserEntity();
        user.setUsername("user-" + UUID.randomUUID());
        user.setPassword("unused");
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    // refreshToken 은 로그인 세션과 묶이므로 실제 로그인으로 받는다.
    private String loginAndGetRefreshToken() throws Exception {
        String credentials = "{\"username\":\"user-" + UUID.randomUUID() + "\",\"password\":\"pw\"}";
        assertThat(postJson("/auth/signup", credentials).statusCode()).isEqualTo(200);
        return objectMapper.readTree(postJson("/auth/login", credentials).body()).path("refreshToken").asText();
    }

    private String expiredToken(String userId, String type) {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        return Jwts.builder()
                .setSubject(userId)
                .claim("typ", type)
                .setIssuedAt(new Date(past.getTime() - 60_000))
                .setExpiration(past)
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS512)
                .compact();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path, String bearer, String deviceId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (deviceId != null) {
            builder.header("X-Device-Id", deviceId);
        }
        return send(builder, bearer);
    }

    private HttpResponse<String> post(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()), bearer);
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)),
                null
        );
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String bearer) throws Exception {
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
