package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.repository.FileRepository;
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

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 전후·로그아웃·계정 전환 때 파일과 계정 데이터의 경계를 확인한다.
 *
 * 현재 구조: 파일 원본은 기기의 앱 내부 저장소에만 있고, 서버에는 파일마다 한 행(제목·앱 내부 경로·진행도·분석 결과)이 있다.
 * - 비회원 데이터 = 이 기기(device_id)에서 만들었고 아직 어떤 계정에도 연결되지 않은(user_id IS NULL) 행
 * - 계정 데이터 = user_id 가 그 계정인 행
 * - 로그인하면 이 기기의 비회원 행에 user_id 만 채운다(새 행을 만들지 않는다).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:account-file-boundary;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
)
class AccountFileBoundaryIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FileRepository fileRepository;

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

    // TEST 1: 비회원으로 A/B/C 등록 → 로그인 → 유실·중복 없이 같은 파일(id)이 계정으로 넘어온다.
    @Test
    void guestFilesMoveToAccountOnLoginWithoutDuplicates() throws Exception {
        String phone = device();
        List<Long> guestIds = new ArrayList<>();
        for (String title : List.of("A.epub", "B.epub", "C.txt")) {
            guestIds.add(api.registerFile(title, "root", null, phone).path("id").asLong());
        }

        String username = username();
        String token = api.signupAndLogin(username, phone);
        // 로그인 처리가 여러 번 실행돼도(앱 재시도 등) 결과가 같아야 한다.
        api.login(username, phone);
        token = api.login(username, phone);

        JsonNode files = api.listFiles("root", token, phone);
        assertThat(ids(files)).containsExactlyInAnyOrderElementsOf(guestIds);
        assertThat(files).allSatisfy(file ->
                assertThat(file.path("uri").asText()).startsWith("file:///data/user/0/com.readme.app/files/books/"));
        assertThat(fileRepository.findAll().stream()
                .filter(file -> phone.equals(file.getDeviceId()))
                .count()).isEqualTo(3);
    }

    // TEST 2: 비회원으로 읽던 위치 → 로그인 → 같은 위치로 이어 읽는다. 폴더 구조도 유지된다.
    @Test
    void readingPositionAndFolderSurviveLogin() throws Exception {
        String phone = device();
        JsonNode folder = api.json(api.send("POST", "/folders", "{\"name\":\"소설\",\"path\":\"root\"}", null, phone));
        String folderId = folder.path("id").asText();
        long fileId = api.registerFile("A.epub", folderId, null, phone).path("id").asLong();
        assertThat(api.send("PATCH", "/files/" + fileId + "/progress",
                "{\"progress\":0.42,\"epubCfi\":\"epubcfi(/6/8!/4/2/1:10)\",\"anchorRatio\":0.3,\"readingPreview\":\"읽던 문장\"}",
                null, phone).statusCode()).isEqualTo(200);

        String token = api.signupAndLogin(username(), phone);

        JsonNode reopened = api.json(api.send("GET", "/files/" + fileId, null, token, phone));
        assertThat(reopened.path("progress").asDouble()).isEqualTo(0.42);
        assertThat(reopened.path("epubCfi").asText()).isEqualTo("epubcfi(/6/8!/4/2/1:10)");
        assertThat(reopened.path("readingPreview").asText()).isEqualTo("읽던 문장");
        JsonNode folders = api.json(api.send("GET", "/folders?path=root", null, token, phone));
        assertThat(folders).extracting(node -> node.path("id").asText()).containsExactly(folderId);
        assertThat(ids(api.listFiles(folderId, token, phone))).containsExactly(fileId);
    }

    // TEST 3: 로그인 상태로 등록 → 로그아웃하면 이 기기의 비회원 화면에는 계정 데이터가 보이지도, 바뀌지도 않는다 → 재로그인하면 그대로다.
    @Test
    void accountDataIsHiddenAndUntouchableAfterLogoutAndBackOnRelogin() throws Exception {
        String phone = device();
        String username = username();
        String token = api.signupAndLogin(username, phone);
        JsonNode folder = api.json(api.send("POST", "/folders", "{\"name\":\"내 폴더\",\"path\":\"root\"}", token, phone));
        long fileId = api.registerFile("Mine.epub", "root", token, phone).path("id").asLong();
        api.send("PATCH", "/files/" + fileId + "/progress", "{\"progress\":0.5,\"recordReadLog\":true}", token, phone);

        // 로그아웃: 같은 폰, 토큰 없음
        assertThat(api.listFiles("root", null, phone)).isEmpty();
        assertThat(api.json(api.send("GET", "/folders?path=root", null, null, phone))).isEmpty();
        assertThat(api.json(api.send("GET", "/files/history", null, null, phone))).isEmpty();
        assertThat(api.json(api.send("GET", "/files/search?keyword=Mine", null, null, phone)).path("content")).isEmpty();
        assertThat(api.json(api.send("GET", "/files/stats", null, null, phone)).path("totalCount").asLong()).isZero();
        assertThat(api.json(api.send("GET", "/ranking/month", null, null, phone))).isEmpty();
        assertThat(api.send("GET", "/files/" + fileId, null, null, phone).statusCode()).isEqualTo(404);
        assertThat(api.send("PATCH", "/files/" + fileId + "/progress", "{\"progress\":0.9}", null, phone).statusCode())
                .isEqualTo(404);
        // 파일과 같이 남의 폴더는 "없는 폴더"(404)로 응답한다.
        assertThat(api.send("PATCH", "/folders/" + folder.path("id").asText(), "{\"name\":\"hacked\"}", null, phone)
                .statusCode()).isEqualTo(404);
        api.send("DELETE", "/files", "{\"ids\":[" + fileId + "]}", null, phone);
        api.send("POST", "/folders/bulk-delete",
                "{\"folderIds\":[" + folder.path("id").asText() + "],\"force\":true}", null, phone);
        // 로그아웃 상태에서 같은 이름으로 추가하려 해도 계정 파일과 중복이라고 안내하지 않는다.
        assertThat(api.json(api.send("GET", "/files/check?title=Mine.epub&path=root", null, null, phone))
                .path("exists").asBoolean()).isFalse();

        // 같은 계정으로 다시 로그인
        token = api.login(username, phone);
        JsonNode files = api.listFiles("root", token, phone);
        assertThat(ids(files)).containsExactly(fileId);
        assertThat(files.get(0).path("progress").asDouble()).isEqualTo(0.5);
        assertThat(api.json(api.send("GET", "/folders?path=root", null, token, phone)))
                .extracting(node -> node.path("name").asText()).containsExactly("내 폴더");
        assertThat(api.json(api.send("GET", "/files/check?title=Mine.epub&path=root", null, token, phone))
                .path("exists").asBoolean()).isTrue();
    }

    // TEST 4: X 로그아웃 → 같은 폰에서 Y 로그인 → X 의 데이터는 Y 에게 연결되지도 보이지도 않는다.
    @Test
    void anotherAccountOnSamePhoneNeverGetsPreviousAccountData() throws Exception {
        String phone = device();
        String userX = username();
        String tokenX = api.signupAndLogin(userX, phone);
        long guestBeforeX = api.registerFile("GuestFirst.txt", "root", null, device()).path("id").asLong();
        long fileOfX = api.registerFile("X.epub", "root", tokenX, phone).path("id").asLong();

        // X 로그아웃 후 비회원으로 하나 추가, 그리고 Y 로그인
        long guestFile = api.registerFile("GuestAfterLogout.txt", "root", null, phone).path("id").asLong();
        String tokenY = api.signupAndLogin(username(), phone);

        assertThat(ids(api.listFiles("root", tokenY, phone))).containsExactly(guestFile);
        assertThat(api.send("GET", "/files/" + fileOfX, null, tokenY, phone).statusCode()).isEqualTo(404);
        assertThat(ids(api.listFiles("root", tokenX, phone))).containsExactly(fileOfX);
        FileEntity stillX = fileRepository.findById(fileOfX).orElseThrow();
        assertThat(stillX.getUser().getUsername()).isEqualTo(userX);
        // 다른 기기의 비회원 파일은 누구에게도 연결되지 않는다.
        assertThat(fileRepository.findById(guestBeforeX).orElseThrow().getUser()).isNull();
    }

    // 파일 등록 요청 본문으로 서버가 정하는 값(id·소유자)을 바꿔 남의 파일을 덮어쓰거나 남의 서재에 넣을 수 없다.
    @Test
    void registrationBodyCannotTargetAnotherAccountsFile() throws Exception {
        String phoneX = device();
        String userX = username();
        String tokenX = api.signupAndLogin(userX, phoneX);
        long fileOfX = api.registerFile("X.epub", "root", tokenX, phoneX).path("id").asLong();
        long userIdX = fileRepository.findById(fileOfX).orElseThrow().getUser().getId();

        String attackerPhone = device();
        String body = "{\"id\":" + fileOfX + ",\"user\":{\"id\":" + userIdX + "},\"title\":\"Evil.txt\",\"type\":\"TXT\","
                + "\"date\":\"2026-09-17T00:00:00Z\",\"uri\":\"file:///evil.txt\",\"path\":\"root\"}";
        HttpResponse<String> response = api.send("POST", "/files", body, null, attackerPhone);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(api.json(response).path("id").asLong()).isNotEqualTo(fileOfX);
        FileEntity untouched = fileRepository.findById(fileOfX).orElseThrow();
        assertThat(untouched.getTitle()).isEqualTo("X.epub");
        assertThat(untouched.getUser().getId()).isEqualTo(userIdX);
        assertThat(ids(api.listFiles("root", tokenX, phoneX))).containsExactly(fileOfX);
    }

    private static List<Long> ids(JsonNode files) {
        List<Long> ids = new ArrayList<>();
        files.forEach(file -> ids.add(file.path("id").asLong()));
        return ids;
    }

    private static String device() {
        return "phone-" + UUID.randomUUID();
    }

    private static String username() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
