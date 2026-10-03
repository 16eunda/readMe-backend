package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.AiAnalysisLog;
import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.Subscription;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.domain.enums.Platform;
import com.ReadMe.demo.domain.enums.SubscriptionStatus;
import com.ReadMe.demo.exception.AiAnalysisFailedException;
import com.ReadMe.demo.repository.AiAnalysisLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.SubscriptionRepository;
import com.ReadMe.demo.repository.UserRepository;
import com.ReadMe.demo.support.TestApi;
import com.ReadMe.demo.worker.AnalysisBacklogScheduler;
import com.ReadMe.demo.worker.AnalysisWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 파일 분석의 상태·중복·누락을 실제 DB 와 HTTP 로 확인한다.
 * 백그라운드 워커 스레드와 주기 점검은 막고, 큐에서 꺼내 분석하는 일과 주기 점검 실행을 테스트가 직접 해서 순서를 고정한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:analysis-lifecycle;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
)
class AnalysisLifecycleIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private AiAnalysisLogRepository analysisLogRepository;
    @Autowired
    private QueueService queueService;
    @Autowired
    private AnalysisService analysisService;
    @Autowired
    private AnalysisBacklogScheduler backlogScheduler;

    @MockitoBean
    private AnalysisWorker analysisWorker;
    @MockitoBean
    private GeminiService geminiService;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(port, objectMapper);
        takeQueue();
        // 앞선 테스트의 사용자가 프리미엄으로 남아 있으면 주기 점검이 그 파일까지 고른다.
        subscriptionRepository.findAll().forEach(subscription -> {
            subscription.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(subscription);
        });
        doReturn(analysis("판타지")).when(geminiService).analyzeText(any(), any());
    }

    // 분석(AI 응답 대기 수 초) 도중 앱이 저장한 읽던 위치·폴더 이동·별점이 분석 결과 저장 때 되돌아가면 안 된다.
    @Test
    void analysisDoesNotRollBackReadingPositionSavedWhileAnalyzing() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        long fileId = api.registerFile("Book.epub", "root", token, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactly(fileId);

        doAnswer(invocation -> {
            // 사용자가 책을 읽는 동안 앱이 보내는 요청들
            api.send("PATCH", "/files/" + fileId + "/progress",
                    "{\"progress\":0.37,\"epubCfi\":\"epubcfi(/6/10!/4/2/1:0)\",\"readingPreview\":\"지금 읽는 곳\"}",
                    token, phone);
            api.send("PATCH", "/files/" + fileId, "{\"path\":\"42\",\"rating\":5}", token, phone);
            return analysis("판타지");
        }).when(geminiService).analyzeText(any(), eq("Book.epub"));

        analysisService.analyze(fileId, false);

        FileEntity file = fileRepository.findById(fileId).orElseThrow();
        assertThat(file.getAnalysisStatus()).isEqualTo("DONE");
        assertThat(file.getAiGenre()).isEqualTo("판타지");
        assertThat(file.getProgress()).isEqualTo(0.37);
        assertThat(file.getEpubCfi()).isEqualTo("epubcfi(/6/10!/4/2/1:0)");
        assertThat(file.getReadingPreview()).isEqualTo("지금 읽는 곳");
        assertThat(file.getPath()).isEqualTo("42");
        assertThat(file.getRating()).isEqualTo(5);
    }

    // TEST 5: 무료일 때 등록한 파일 → 구독 → 열지 않아도 백그라운드에서 한 번씩 분석된다.
    @Test
    void filesRegisteredWhileFreeAreAnalyzedInBackgroundAfterSubscribing() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        List<Long> ids = new ArrayList<>();
        for (String title : List.of("A.epub", "B.epub", "C.epub", "D.txt")) {
            ids.add(api.registerFile(title, "root", token, phone).path("id").asLong());
        }
        assertThat(statuses(ids)).containsOnly("PENDING");

        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).as("무료 사용자 파일은 분석 대상이 아니다").isEmpty();

        activatePremium(username);
        // 주기 점검이 여러 번 돌아도(= 구독 콜백 중복) 파일마다 한 번만 대기한다.
        backlogScheduler.enqueueBacklog();
        backlogScheduler.enqueueBacklog();
        List<Long> queued = takeQueue();
        assertThat(queued).containsExactlyInAnyOrderElementsOf(ids);

        queued.forEach(queueService::enqueue);
        drainAndAnalyze();

        assertThat(statuses(ids)).containsOnly("DONE");
        verify(geminiService, times(4)).analyzeText(any(), any());
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).isEmpty();
    }

    // TEST 6: 대기 중 서버 재시작(메모리 큐 유실) + 분석 도중 꺼진 파일 → 다시 채워지고, 중복되지 않는다.
    @Test
    void queueLostOnServerRestartIsRebuiltWithoutDuplicates() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        List<Long> ids = new ArrayList<>();
        for (String title : List.of("R1.epub", "R2.epub", "R3.epub", "R4.epub")) {
            ids.add(api.registerFile(title, "root", token, phone).path("id").asLong());
        }
        // 주기 점검이 여러 번 돌아도 큐에는 파일마다 한 번
        backlogScheduler.enqueueBacklog();
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactlyInAnyOrderElementsOf(ids);

        // 서버 재시작: 큐는 비었다(위에서 비움). R3 은 분석 도중 서버가 꺼졌고, R4 는 다른 서버가 방금 분석을 시작했다.
        setAnalysis(ids.get(2), "PROCESSING", LocalDateTime.now().minusHours(1));
        setAnalysis(ids.get(3), "PROCESSING", LocalDateTime.now());

        backlogScheduler.enqueueBacklog();
        List<Long> rebuilt = takeQueue();
        assertThat(rebuilt).containsExactlyInAnyOrder(ids.get(0), ids.get(1), ids.get(2));

        rebuilt.forEach(queueService::enqueue);
        drainAndAnalyze();
        assertThat(statuses(ids.subList(0, 3))).containsOnly("DONE");
        assertThat(fileRepository.findById(ids.get(3)).orElseThrow().getAnalysisStatus()).isEqualTo("PROCESSING");
        verify(geminiService, times(3)).analyzeText(any(), any());
    }

    // 워커가 분석 중일 때 사용자가 AI 정보를 열면, AI 를 한 번 더 부르지 않고 같은 결과를 받는다.
    @Test
    void openingAiInfoWhileWorkerAnalyzesWaitsForTheSameResult() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        long fileId = api.registerFile("Same.epub", "root", token, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactly(fileId);

        CountDownLatch workerCalledAi = new CountDownLatch(1);
        CountDownLatch aiResponds = new CountDownLatch(1);
        doAnswer(invocation -> {
            workerCalledAi.countDown();
            aiResponds.await(10, TimeUnit.SECONDS);
            return analysis("SF");
        }).when(geminiService).analyzeText(any(), eq("Same.epub"));

        Thread worker = new Thread(() -> analysisService.analyze(fileId, false));
        worker.start();
        assertThat(workerCalledAi.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<HttpResponse<String>> aiInfo = CompletableFuture.supplyAsync(() -> {
            try {
                return api.send("GET", "/files/" + fileId + "/ai-info", null, token, phone);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(800);
        assertThat(aiInfo).isNotDone();
        aiResponds.countDown();
        worker.join(10_000);

        JsonNode response = api.json(aiInfo.get(15, TimeUnit.SECONDS));
        assertThat(response.path("analysisStatus").asText()).isEqualTo("DONE");
        assertThat(response.path("genre").asText()).isEqualTo("SF");
        verify(geminiService, times(1)).analyzeText(any(), any());
    }

    // TEST 7: 한 파일 분석 중 네트워크 오류 → 그 파일만 실패, 같은 날 반복 호출하지 않고, 직접 열거나 다음 날 다시 분석된다.
    @Test
    void aiFailureMarksOnlyThatFileAndItStaysRetryable() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        long a = api.registerFile("NetA.epub", "root", token, phone).path("id").asLong();
        long b = api.registerFile("NetB.epub", "root", token, phone).path("id").asLong();
        long c = api.registerFile("NetC.epub", "root", token, phone).path("id").asLong();
        long d = api.registerFile("NetD.epub", "root", token, phone).path("id").asLong();
        doThrow(new AiAnalysisFailedException("timeout")).when(geminiService).analyzeText(any(), eq("NetB.epub"));
        doThrow(new AiAnalysisFailedException("timeout")).when(geminiService).analyzeText(any(), eq("NetD.epub"));

        backlogScheduler.enqueueBacklog();
        drainAndAnalyze();

        assertThat(statuses(List.of(a, c))).containsOnly("DONE");
        assertThat(statuses(List.of(b, d))).containsOnly("FAILED");
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).as("실패한 파일을 1분마다 다시 부르지 않는다").isEmpty();

        doReturn(analysis("로맨스")).when(geminiService).analyzeText(any(), eq("NetB.epub"));
        doReturn(analysis("로맨스")).when(geminiService).analyzeText(any(), eq("NetD.epub"));

        // 사용자가 직접 열면 바로 다시 분석한다.
        JsonNode retried = api.json(api.send("GET", "/files/" + b + "/ai-info", null, token, phone));
        assertThat(retried.path("analysisStatus").asText()).isEqualTo("DONE");
        assertThat(retried.path("genre").asText()).isEqualTo("로맨스");

        // 열지 않은 파일은 다음 날 주기 점검이 다시 넣는다.
        setAnalysis(d, "FAILED", LocalDateTime.now().minusDays(1));
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactly(d);
        queueService.enqueue(d);
        drainAndAnalyze();
        assertThat(fileRepository.findById(d).orElseThrow().getAnalysisStatus()).isEqualTo("DONE");
    }

    // TEST 8: 이미 분석한 파일을 다시 열고 읽어도 다시 분석하지 않는다.
    @Test
    void analyzedFileIsNotAnalyzedAgainWhenReopened() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        long fileId = api.registerFile("Done.epub", "root", token, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        drainAndAnalyze();

        // 앱 재실행 후 다시 열기
        api.send("POST", "/files/" + fileId + "/read", null, token, phone);
        api.send("PATCH", "/files/" + fileId + "/progress", "{\"progress\":0.6,\"recordReadLog\":true}", token, phone);
        JsonNode aiInfo = api.json(api.send("GET", "/files/" + fileId + "/ai-info", null, token, phone));
        backlogScheduler.enqueueBacklog();

        assertThat(aiInfo.path("analysisStatus").asText()).isEqualTo("DONE");
        assertThat(takeQueue()).isEmpty();
        verify(geminiService, times(1)).analyzeText(any(), any());
    }

    // TEST 9: 구독 만료 → 결과는 지우지 않고 가리기만 → 재구독하면 그대로 다시 보이고, 만료 중 등록한 파일만 새로 분석한다.
    @Test
    void expiredSubscriptionHidesResultsAndResubscribingReusesThem() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        Subscription subscription = activatePremium(username);
        long analyzed = api.registerFile("Kept.epub", "root", token, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        drainAndAnalyze();

        subscription.setStatus(SubscriptionStatus.EXPIRED);
        subscription.setExpiresAt(Instant.now().minusSeconds(60));
        subscriptionRepository.save(subscription);

        assertThat(api.json(api.send("GET", "/files/" + analyzed + "/ai-info", null, token, phone))
                .path("analysisStatus").asText()).isEqualTo("PREMIUM_REQUIRED");
        assertThat(fileRepository.findById(analyzed).orElseThrow().getAiGenre()).isEqualTo("판타지");
        long whileExpired = api.registerFile("New.epub", "root", token, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).isEmpty();

        activatePremium(username);

        JsonNode reused = api.json(api.send("GET", "/files/" + analyzed + "/ai-info", null, token, phone));
        assertThat(reused.path("analysisStatus").asText()).isEqualTo("DONE");
        assertThat(reused.path("genre").asText()).isEqualTo("판타지");
        verify(geminiService, times(1)).analyzeText(any(), any());

        // 만료 중 등록한 파일은 재구독 후 주기 점검이 한 번만 대기열에 넣는다.
        api.send("PATCH", "/files/" + whileExpired + "/progress", "{\"progress\":0.3}", token, phone);
        assertThat(fileRepository.findById(whileExpired).orElseThrow().getProgress()).isEqualTo(0.3);
        backlogScheduler.enqueueBacklog();
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactly(whileExpired);
    }

    // 11번 시나리오: 비회원 파일 20개 → 프리미엄 계정으로 로그인 → 같은 파일(id)이 계정으로 넘어가 한 번씩만 분석된다.
    @Test
    void guestFilesLinkedAtLoginAreAnalyzedOnceUnderTheAccount() throws Exception {
        String phone = device();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(api.registerFile("Guest" + i + ".epub", "root", null, phone).path("id").asLong());
        }
        String username = username();
        assertThat(api.send("POST", "/auth/signup",
                "{\"username\":\"" + username + "\",\"password\":\"pw\"}", null, null).statusCode()).isEqualTo(200);
        activatePremium(username);

        String token = api.login(username, phone);
        api.login(username, phone);

        backlogScheduler.enqueueBacklog();
        backlogScheduler.enqueueBacklog();
        List<Long> queued = takeQueue();
        assertThat(queued).containsExactlyInAnyOrderElementsOf(ids);
        queued.forEach(queueService::enqueue);
        // 워커는 한 번에 하나씩, 계정당 하루 자동 분석 10건까지만 AI 를 부른다.
        drainAndAnalyze();

        UserEntity account = userRepository.findByUsername(username).orElseThrow();
        List<FileEntity> rows = fileRepository.findAll().stream()
                .filter(file -> phone.equals(file.getDeviceId()))
                .toList();
        assertThat(rows).hasSize(20).allSatisfy(file -> assertThat(file.getUser().getId()).isEqualTo(account.getId()));
        assertThat(rows).filteredOn(file -> "DONE".equals(file.getAnalysisStatus())).hasSize(10);
        assertThat(rows).filteredOn(file -> "LIMIT_EXCEEDED".equals(file.getAnalysisStatus())).hasSize(10);
        verify(geminiService, times(10)).analyzeText(any(), any());
        assertThat(api.send("GET", "/files/" + ids.get(0) + "/ai-info", null, null, phone).statusCode())
                .as("로그인 후에는 비회원 화면에서 계정 파일을 볼 수 없다").isEqualTo(404);

        // 같은 날에는 한도 초과 파일을 다시 부르지 않고, 다음 날 나머지를 분석한다.
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).isEmpty();
        moveToYesterday(rows);
        backlogScheduler.enqueueBacklog();
        takeQueue().forEach(queueService::enqueue);
        drainAndAnalyze();
        assertThat(statuses(ids)).containsOnly("DONE");
        verify(geminiService, times(20)).analyzeText(any(), any());
    }

    // 비회원이 구독한 기기에서 분석이 진행되는 도중 로그인해도, 결과는 같은 파일(이제 계정 소유)에 한 번만 저장된다.
    @Test
    void loginDuringAnalysisKeepsResultOnTheSameFile() throws Exception {
        String phone = device();
        Subscription guestSubscription = new Subscription();
        guestSubscription.setDeviceId(phone);
        guestSubscription.setPlatform(Platform.ANDROID);
        guestSubscription.setPurchaseToken("token-" + UUID.randomUUID());
        guestSubscription.setStatus(SubscriptionStatus.ACTIVE);
        guestSubscription.setExpiresAt(Instant.now().plusSeconds(3600));
        subscriptionRepository.save(guestSubscription);
        long fileId = api.registerFile("During.epub", "root", null, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactly(fileId);

        String username = username();
        String[] token = new String[1];
        doAnswer(invocation -> {
            token[0] = api.signupAndLogin(username, phone);
            return analysis("에세이");
        }).when(geminiService).analyzeText(any(), eq("During.epub"));

        analysisService.analyze(fileId, false);

        FileEntity file = fileRepository.findById(fileId).orElseThrow();
        assertThat(file.getUser().getUsername()).isEqualTo(username);
        assertThat(file.getAnalysisStatus()).isEqualTo("DONE");
        JsonNode aiInfo = api.json(api.send("GET", "/files/" + fileId + "/ai-info", null, token[0], phone));
        assertThat(aiInfo.path("genre").asText()).isEqualTo("에세이");
        assertThat(fileRepository.findAll()).filteredOn(row -> phone.equals(row.getDeviceId())).hasSize(1);
        verify(geminiService, times(1)).analyzeText(any(), any());
    }

    // 등록·읽기는 분석을 직접 요청하지 않는다. 구독자의 분석 안 된 책은 주기 점검 한 곳에서만 대기열에 들어간다.
    @Test
    void registeringAndReadingLeaveAnalysisToBackground() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        long fileId = api.registerFile("Later.epub", "root", token, phone).path("id").asLong();
        api.send("POST", "/files/" + fileId + "/read", null, token, phone);
        api.send("PATCH", "/files/" + fileId + "/progress", "{\"progress\":0.2}", token, phone);

        assertThat(takeQueue()).isEmpty();
        assertThat(fileRepository.findById(fileId).orElseThrow().getAnalysisStatus()).isEqualTo("PENDING");

        backlogScheduler.enqueueBacklog();
        assertThat(takeQueue()).containsExactly(fileId);
    }

    // 하루 한도보다 밀린 책이 많으면 최근 읽은 책 → 최근 등록한 책 순으로 분석하고, 가장 오래 안 읽은 책이 다음 날로 밀린다.
    @Test
    void backlogAnalyzesRecentlyReadBooksFirstThenNewestRegistered() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(api.registerFile("Order" + i + ".epub", "root", token, phone).path("id").asLong());
        }
        // 가장 먼저 등록한 두 권을 읽었다. Order1 을 더 최근에 읽었다.
        setLastReadAt(ids.get(0), LocalDateTime.now().minusDays(2));
        setLastReadAt(ids.get(1), LocalDateTime.now().minusHours(1));
        activatePremium(username);

        backlogScheduler.enqueueBacklog();
        List<Long> queued = takeQueue();

        List<Long> expected = new ArrayList<>(List.of(ids.get(1), ids.get(0)));
        for (int i = 11; i >= 2; i--) {
            expected.add(ids.get(i));
        }
        assertThat(queued).containsExactlyElementsOf(expected);

        queued.forEach(queueService::enqueue);
        drainAndAnalyze();
        assertThat(statuses(expected.subList(0, 10))).containsOnly("DONE");
        assertThat(statuses(List.of(ids.get(3), ids.get(2)))).containsOnly("LIMIT_EXCEEDED");
    }

    // 같은 제목으로 이미 분석된 내 책이 있으면 AI 를 부르지 않고 복사하므로, 오늘 한도를 다 썼어도 바로 채운다.
    @Test
    void sameTitleCopyIsNotBlockedByDailyLimit() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        activatePremium(username);
        api.registerFile("Twice.epub", "root", token, phone);
        backlogScheduler.enqueueBacklog();
        drainAndAnalyze();
        useUpTodayLimit(username);

        long copy = api.registerFile("Twice.epub", "root", token, phone).path("id").asLong();
        backlogScheduler.enqueueBacklog();
        drainAndAnalyze();

        FileEntity copied = fileRepository.findById(copy).orElseThrow();
        assertThat(copied.getAnalysisStatus()).isEqualTo("DONE");
        assertThat(copied.getAiGenre()).isEqualTo("판타지");
        verify(geminiService, times(1)).analyzeText(any(), any());
    }

    // ── helpers ──

    private void setLastReadAt(Long fileId, LocalDateTime readAt) {
        FileEntity file = fileRepository.findById(fileId).orElseThrow();
        file.setLastReadAt(readAt);
        fileRepository.save(file);
    }

    private void useUpTodayLimit(String username) {
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        for (int i = 0; i < 10; i++) {
            analysisLogRepository.save(AiAnalysisLog.builder()
                    .user(user)
                    .analyzedAt(LocalDateTime.now())
                    .build());
        }
    }

    private List<Long> takeQueue() {
        List<Long> ids = new ArrayList<>();
        Long id;
        while ((id = queueService.dequeue()) != null) {
            ids.add(id);
        }
        return ids;
    }

    /** 워커 한 대가 큐를 비울 때까지 하나씩 분석하는 것과 같다. */
    private void drainAndAnalyze() {
        Long id;
        while ((id = queueService.dequeue()) != null) {
            analysisService.analyze(id, false);
        }
    }

    private List<String> statuses(List<Long> ids) {
        return ids.stream()
                .map(id -> fileRepository.findById(id).orElseThrow().getAnalysisStatus())
                .toList();
    }

    private void setAnalysis(Long fileId, String status, LocalDateTime startedAt) {
        FileEntity file = fileRepository.findById(fileId).orElseThrow();
        file.setAnalysisStatus(status);
        file.setAnalysisStartedAt(startedAt);
        fileRepository.save(file);
    }

    /** 하루가 지난 것처럼: 오늘의 분석 사용 기록과 마지막 시도 시각을 어제로 옮긴다. */
    private void moveToYesterday(List<FileEntity> files) {
        for (AiAnalysisLog log : analysisLogRepository.findAll()) {
            log.setAnalyzedAt(log.getAnalyzedAt().minusDays(1));
            analysisLogRepository.save(log);
        }
        for (FileEntity file : files) {
            FileEntity fresh = fileRepository.findById(file.getId()).orElseThrow();
            if (fresh.getAnalysisStartedAt() != null) {
                fresh.setAnalysisStartedAt(fresh.getAnalysisStartedAt().minusDays(1));
                fileRepository.save(fresh);
            }
        }
    }

    private Subscription activatePremium(String username) {
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setPlatform(Platform.ANDROID);
        subscription.setProductId("monthly_2900");
        subscription.setPurchaseToken("token-" + UUID.randomUUID());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartedAt(Instant.now().minusSeconds(60));
        subscription.setExpiresAt(Instant.now().plusSeconds(30L * 24 * 3600));
        return subscriptionRepository.save(subscription);
    }

    private static Map<String, String> analysis(String genre) {
        return Map.of(
                "genre", genre,
                "keywords", "모험,마법",
                "mood", "긴장감",
                "info", "내용 설명",
                "summary", "한 줄 요약",
                "target", "성인"
        );
    }

    private static String device() {
        return "phone-" + UUID.randomUUID();
    }

    private static String username() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
