package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.FolderRepository;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.ReadMe.demo.repository.UserRepository;
import com.ReadMe.demo.service.GeminiService;
import com.ReadMe.demo.support.TestApi;
import com.ReadMe.demo.worker.AnalysisWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 출시 전 점검: 실제 사용자 흐름과, 같은 요청 반복·동시 요청·잘못된 입력에서 서버가 버티는지 확인한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:production-readiness;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
)
class ProductionReadinessIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private FileReadLogRepository readLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    // 분석은 이 테스트의 관심사가 아니다. 백그라운드 워커와 외부 AI 호출이 끼어들지 않게 막는다.
    @MockitoBean
    private AnalysisWorker analysisWorker;

    @MockitoBean
    private GeminiService geminiService;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, objectMapper);
    }

    // 가입 → 로그인 → 등록 → 목록 → 열기 → 진행도 저장 → 최근 기록 → 앱 종료 → refreshToken 으로 복귀 → 이어읽기 → 삭제
    @Test
    void fullReadingJourneySurvivesAppRestartAndCleanDelete() throws Exception {
        String phone = device();
        String username = username();
        assertThat(api.send("POST", "/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"pw\"}", null, null).statusCode()).isEqualTo(200);

        HttpResponse<String> loginResponse = api.send("POST", "/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"pw\",\"deviceId\":\"" + phone + "\"}", null, null);
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        JsonNode login = api.json(loginResponse);
        String accessToken = login.path("accessToken").asText();
        String refreshToken = login.path("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank().isNotEqualTo(accessToken);

        long fileId = api.registerFile("Journey.epub", "root", accessToken, phone).path("id").asLong();
        assertThat(ids(api.listFiles("root", accessToken, phone))).containsExactly(fileId);

        // 파일 열기: 정보 조회 + 최근 읽은 시각 기록
        assertThat(api.send("GET", "/files/" + fileId, null, accessToken, phone).statusCode()).isEqualTo(200);
        assertThat(api.send("POST", "/files/" + fileId + "/read", null, accessToken, phone).statusCode()).isEqualTo(200);

        // 진행도 저장. 앱은 anchorRatio 를 1 처럼 정수로 보낼 수도 있다.
        String progressBody = "{\"progress\":0.42,\"recordReadLog\":true,\"epubCfi\":\"epubcfi(/6/14!/4/2/10/1:120)\","
                + "\"anchorRatio\":1,\"readingPreview\":\"지금 읽는 부분\"}";
        assertThat(api.send("PATCH", "/files/" + fileId + "/progress", progressBody, accessToken, phone).statusCode())
                .isEqualTo(200);
        // 같은 저장을 앱이 재시도해도 결과가 같다. (같은 날 읽기 기록은 1개)
        assertThat(api.send("PATCH", "/files/" + fileId + "/progress", progressBody, accessToken, phone).statusCode())
                .isEqualTo(200);
        assertThat(readLogCount(fileId)).isEqualTo(1);

        JsonNode history = api.json(api.send("GET", "/files/history", null, accessToken, phone));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("id").asLong()).isEqualTo(fileId);
        assertThat(history.get(0).path("lastReadAt").isNull()).isFalse();

        // 앱 종료 후 재실행: accessToken 이 만료됐다고 보고 refreshToken 으로 새 토큰을 받는다.
        HttpResponse<String> refreshed = api.send("POST", "/auth/refresh", null, refreshToken, null);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String newAccessToken = api.json(refreshed).path("accessToken").asText();
        assertThat(api.json(refreshed).path("refreshToken").asText()).isNotBlank();
        assertThat(api.send("GET", "/auth/users/me", null, newAccessToken, phone).statusCode()).isEqualTo(200);

        // 이어읽기: 저장한 위치가 그대로 돌아온다.
        JsonNode reopened = api.json(api.send("GET", "/files/" + fileId, null, newAccessToken, phone));
        assertThat(reopened.path("progress").asDouble()).isEqualTo(0.42);
        assertThat(reopened.path("epubCfi").asText()).isEqualTo("epubcfi(/6/14!/4/2/10/1:120)");
        assertThat(reopened.path("anchorRatio").asDouble()).isEqualTo(1.0);
        assertThat(reopened.path("readingPreview").asText()).isEqualTo("지금 읽는 부분");

        // 삭제. 네트워크 재시도로 같은 삭제가 다시 와도 오류가 나지 않는다.
        String deleteBody = "{\"ids\":[" + fileId + "]}";
        assertThat(api.send("DELETE", "/files", deleteBody, newAccessToken, phone).statusCode()).isEqualTo(200);
        assertThat(api.send("DELETE", "/files", deleteBody, newAccessToken, phone).statusCode()).isEqualTo(200);

        assertThat(api.listFiles("root", newAccessToken, phone)).isEmpty();
        assertThat(api.json(api.send("GET", "/files/history", null, newAccessToken, phone))).isEmpty();
        assertThat(api.send("GET", "/files/" + fileId, null, newAccessToken, phone).statusCode()).isEqualTo(404);
        assertThat(readLogCount(fileId)).isZero();
    }

    // 앱의 리뷰 입력창은 300자까지 받는다. 저장에 실패하면 사용자가 쓴 리뷰가 그대로 사라진다.
    @Test
    void reviewUpToTheAppLimitIsSaved() throws Exception {
        String phone = device();
        long fileId = api.registerFile("Review.txt", "root", null, phone).path("id").asLong();
        String review = "가".repeat(300);

        HttpResponse<String> response = api.send("PATCH", "/files/" + fileId,
                "{\"review\":\"" + review + "\",\"rating\":5}", null, phone);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fileRepository.findById(fileId).orElseThrow().getReview()).isEqualTo(review);
    }

    // 앱의 이동 화면은 자기 자신과 바로 아래 폴더만 숨긴다. 손자 폴더로 옮기면 폴더가 고리를 이뤄 통째로 사라진다.
    @Test
    void folderCannotBeMovedIntoItselfOrItsDescendant() throws Exception {
        String phone = device();
        long a = createFolder("A", "root", phone);
        long b = createFolder("B", String.valueOf(a), phone);
        long c = createFolder("C", String.valueOf(b), phone);

        assertThat(api.send("PUT", "/folders/" + a, "{\"path\":\"" + c + "\"}", null, phone).statusCode()).isEqualTo(400);
        assertThat(api.send("PUT", "/folders/" + a, "{\"path\":\"" + a + "\"}", null, phone).statusCode()).isEqualTo(400);
        assertThat(api.send("PATCH", "/folders/" + a, "{\"path\":\"" + b + "\"}", null, phone).statusCode()).isEqualTo(400);
        assertThat(folderRepository.findById(a).orElseThrow().getPath()).isEqualTo("root");

        // 정상 이동은 그대로 된다.
        long d = createFolder("D", "root", phone);
        assertThat(api.send("PUT", "/folders/" + c, "{\"path\":\"" + d + "\"}", null, phone).statusCode()).isEqualTo(200);
        assertThat(api.send("PUT", "/folders/" + c, "{\"path\":\"root\"}", null, phone).statusCode()).isEqualTo(200);
    }

    // 이미 고리가 생긴 폴더를 지워도 요청이 끝나야 한다. 끝나지 않으면 DB 연결을 붙잡은 채 메모리를 다 쓸 때까지 돈다.
    @Test
    void deletingFoldersThatAlreadyFormACycleFinishes() throws Exception {
        String phone = device();
        long a = createFolder("A", "root", phone);
        long b = createFolder("B", String.valueOf(a), phone);
        FolderEntity folderA = folderRepository.findById(a).orElseThrow();
        folderA.setPath(String.valueOf(b));
        folderRepository.save(folderA);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/folders/bulk-delete"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", phone)
                .POST(HttpRequest.BodyPublishers.ofString("{\"folderIds\":[" + a + "],\"force\":true}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(folderRepository.findById(a)).isEmpty();
        assertThat(folderRepository.findById(b)).isEmpty();
    }

    // 폴더를 지우면 안에 든 파일(하위 폴더 포함)도 함께 지워진다. 파일 path 는 문자열, 폴더 id 는 숫자다.
    @Test
    void deletingFolderRemovesFilesInsideIt() throws Exception {
        String phone = device();
        String token = api.signupAndLogin(username(), phone);
        long parent = createFolder("Parent", "root", token, phone);
        long child = createFolder("Child", String.valueOf(parent), token, phone);
        long inParent = api.registerFile("InParent.txt", String.valueOf(parent), token, phone).path("id").asLong();
        long inChild = api.registerFile("InChild.epub", String.valueOf(child), token, phone).path("id").asLong();
        long outside = api.registerFile("Outside.txt", "root", token, phone).path("id").asLong();

        // force 없이 지우면 안에 든 것을 알려 주고 아무것도 지우지 않는다.
        HttpResponse<String> warn = api.send("POST", "/folders/bulk-delete",
                "{\"folderIds\":[" + parent + "],\"force\":false}", token, phone);
        assertThat(warn.statusCode()).isEqualTo(409);
        assertThat(api.json(warn).path("data").path("fileCount").asInt()).isEqualTo(2);

        assertThat(api.send("POST", "/folders/bulk-delete",
                "{\"folderIds\":[" + parent + "],\"force\":true}", token, phone).statusCode()).isEqualTo(200);

        assertThat(fileRepository.findById(inParent)).isEmpty();
        assertThat(fileRepository.findById(inChild)).isEmpty();
        assertThat(fileRepository.findById(outside)).isPresent();
        assertThat(folderRepository.findById(child)).isEmpty();
    }

    // 남의 폴더는 바꾸거나 지울 수 없고, 없는 폴더는 서버 오류(500)가 아니라 404 다.
    @Test
    void foldersOfAnotherAccountCannotBeChangedAndMissingFolderIs404() throws Exception {
        String ownerPhone = device();
        String ownerToken = api.signupAndLogin(username(), ownerPhone);
        long folderId = createFolder("Mine", "root", ownerToken, ownerPhone);

        String otherPhone = device();
        String otherToken = api.signupAndLogin(username(), otherPhone);

        assertThat(api.send("PATCH", "/folders/" + folderId, "{\"name\":\"hacked\"}", otherToken, otherPhone).statusCode())
                .isEqualTo(404);
        assertThat(api.send("PUT", "/folders/" + folderId, "{\"path\":\"root\"}", otherToken, otherPhone).statusCode())
                .isEqualTo(404);
        assertThat(api.send("POST", "/folders/bulk-delete",
                "{\"folderIds\":[" + folderId + "],\"force\":true}", otherToken, otherPhone).statusCode()).isEqualTo(404);
        // 비회원이 deviceId 만 바꿔 넣어도 마찬가지다.
        assertThat(api.send("DELETE", "/folders/" + folderId, "{\"id\":" + folderId + "}", null, ownerPhone).statusCode())
                .isEqualTo(404);

        FolderEntity folder = folderRepository.findById(folderId).orElseThrow();
        assertThat(folder.getName()).isEqualTo("Mine");

        // 이미 지운 폴더를 다시 지우는 요청(연타·재시도)
        assertThat(api.send("DELETE", "/folders/999999", "{\"id\":999999}", ownerToken, ownerPhone).statusCode())
                .isEqualTo(404);
        assertThat(api.send("PATCH", "/folders/999999", "{\"name\":\"x\"}", ownerToken, ownerPhone).statusCode())
                .isEqualTo(404);
    }

    // 잘못된 요청은 400 이다. 500 이면 운영 로그에서 실제 장애와 구분되지 않는다.
    @Test
    void malformedRequestsAreBadRequestNotServerError() throws Exception {
        String phone = device();
        long fileId = api.registerFile("Input.txt", "root", null, phone).path("id").asLong();

        assertThat(api.send("PATCH", "/files/" + fileId + "/progress", "{\"progress\":", null, phone).statusCode())
                .isEqualTo(400);
        assertThat(api.send("GET", "/files/not-a-number", null, null, phone).statusCode()).isEqualTo(400);
        assertThat(api.send("PATCH", "/files/" + fileId, "{\"rating\":\"five\"}", null, phone).statusCode())
                .isEqualTo(400);
        assertThat(api.send("PATCH", "/files/" + fileId, "{\"title\":123}", null, phone).statusCode()).isEqualTo(400);
        assertThat(api.send("PATCH", "/files/" + fileId + "/progress", "{\"epubCfi\":42}", null, phone).statusCode())
                .isEqualTo(400);
        assertThat(api.send("GET", "/files/search?keyword=a&sort=aiContent,desc", null, null, phone).statusCode())
                .isEqualTo(400);
        assertThat(api.send("GET", "/files?path=root&page=-1&size=15&sort=date,desc", null, null, phone).statusCode())
                .isEqualTo(400);
        assertThat(api.send("GET", "/files?path=root&page=0&size=100000&sort=date,desc", null, null, phone).statusCode())
                .isEqualTo(400);
        assertThat(api.send("DELETE", "/files", "{}", null, phone).statusCode()).isEqualTo(400);
        assertThat(api.send("POST", "/auth/signup", "{\"password\":\"pw\"}", null, null).statusCode()).isEqualTo(400);
        assertThat(api.send("POST", "/auth/signup", "{\"username\":\" \",\"password\":\"pw\"}", null, null).statusCode())
                .isEqualTo(400);
        assertThat(api.send("POST", "/auth/signup", "{\"username\":\"" + username() + "\",\"password\":\"\"}", null, null)
                .statusCode()).isEqualTo(400);

        // 프리미엄 사용자가 없는 달(13월)을 요청해도 400
        Subscription premium = new Subscription();
        premium.setDeviceId(phone);
        premium.setPlatform(Platform.ANDROID);
        premium.setStatus(SubscriptionStatus.ACTIVE);
        premium.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(premium);
        assertThat(api.send("GET", "/ranking/month?year=2026&month=13", null, null, phone).statusCode()).isEqualTo(400);

        // 파일은 그대로다.
        assertThat(fileRepository.findById(fileId).orElseThrow().getTitle()).isEqualTo("Input.txt");
    }

    // 가입 버튼 연타·네트워크 재시도로 같은 가입이 동시에 와도 계정은 하나이고 500 은 없다.
    @Test
    void concurrentSignupWithSameUsernameCreatesOneAccount() throws Exception {
        String username = username();
        List<Integer> statuses = runConcurrently(8, () -> api.send("POST", "/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"pw\"}", null, null).statusCode());

        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(200, 400, 409));
        assertThat(userRepository.findByUsername(username)).isPresent();
    }

    // 여러 기기에서 같은 책 진행도가 동시에 저장되고, 같은 파일 삭제가 동시에 와도 500 이 나지 않는다.
    @Test
    void concurrentProgressSavesAndDeletesDoNotFail() throws Exception {
        String phone = device();
        String token = api.signupAndLogin(username(), phone);
        long fileId = api.registerFile("Concurrent.epub", "root", token, phone).path("id").asLong();

        List<Integer> saves = runConcurrently(10, () -> api.send("PATCH", "/files/" + fileId + "/progress",
                "{\"progress\":0.5,\"recordReadLog\":false}", token, phone).statusCode());
        assertThat(saves).containsOnly(200);
        assertThat(fileRepository.findById(fileId).orElseThrow().getProgress()).isEqualTo(0.5);

        List<Integer> deletes = runConcurrently(5, () -> api.send("DELETE", "/files",
                "{\"ids\":[" + fileId + "]}", token, phone).statusCode());
        assertThat(deletes).containsOnly(200);
        assertThat(fileRepository.findById(fileId)).isEmpty();
    }

    // 두 기기가 같은 책의 그날 첫 진행도를 동시에 저장하면 읽기 기록이 2개 생길 수 있다.
    // 그 뒤로도 그 책의 진행도 저장이 계속 성공해야 한다. (실패하면 그날 읽은 위치가 서버에 남지 않는다)
    @Test
    void progressSaveKeepsWorkingWhenTodayAlreadyHasTwoReadLogs() throws Exception {
        String phone = device();
        long fileId = api.registerFile("TwoDevices.epub", "root", null, phone).path("id").asLong();
        com.ReadMe.demo.domain.FileEntity file = fileRepository.findById(fileId).orElseThrow();
        for (int i = 0; i < 2; i++) {
            com.ReadMe.demo.domain.FileReadLog log = new com.ReadMe.demo.domain.FileReadLog();
            log.setFile(file);
            log.setReadAt(java.time.LocalDateTime.now());
            readLogRepository.save(log);
        }

        HttpResponse<String> response = api.send("PATCH", "/files/" + fileId + "/progress",
                "{\"progress\":0.7,\"recordReadLog\":true}", null, phone);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fileRepository.findById(fileId).orElseThrow().getProgress()).isEqualTo(0.7);
        assertThat(readLogCount(fileId)).isEqualTo(2);
    }

    // 앱이 여러 화면에서 동시에 401 을 받아 같은 refreshToken 으로 동시에 재발급해도 모두 성공한다.
    @Test
    void concurrentRefreshWithSameTokenAllSucceed() throws Exception {
        String phone = device();
        String username = username();
        api.signupAndLogin(username, phone);
        String refreshToken = api.json(api.send("POST", "/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"pw\",\"deviceId\":\"" + phone + "\"}", null, null))
                .path("refreshToken").asText();

        List<Integer> statuses = runConcurrently(6,
                () -> api.send("POST", "/auth/refresh", null, refreshToken, null).statusCode());
        assertThat(statuses).containsOnly(200);
    }

    private long createFolder(String name, String path, String deviceId) throws Exception {
        return createFolder(name, path, null, deviceId);
    }

    private long createFolder(String name, String path, String accessToken, String deviceId) throws Exception {
        HttpResponse<String> response = api.send("POST", "/folders",
                "{\"name\":\"" + name + "\",\"path\":\"" + path + "\"}", accessToken, deviceId);
        assertThat(response.statusCode()).isEqualTo(200);
        return api.json(response).path("id").asLong();
    }

    private long readLogCount(long fileId) {
        return readLogRepository.findAll().stream()
                .filter(log -> log.getFile().getId() == fileId)
                .count();
    }

    private <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Long> ids(JsonNode files) {
        List<Long> ids = new ArrayList<>();
        files.forEach(file -> ids.add(file.path("id").asLong()));
        return ids;
    }

    private static String device() {
        return "device-" + UUID.randomUUID();
    }

    private static String username() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
