package com.ReadMe.demo.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱과 같은 방식으로 실제 HTTP 요청을 보내는 테스트 도우미.
 * 로그인 상태는 Authorization 헤더, 비회원은 X-Device-Id 로만 구분된다.
 * 앱의 로그아웃은 서버 호출 없이 토큰만 버리므로, 테스트에서는 accessToken 을 null 로 보내면 로그아웃 상태다.
 */
public class TestApi {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final int port;

    public TestApi(int port, ObjectMapper objectMapper) {
        this.port = port;
        this.objectMapper = objectMapper;
    }

    /** 가입 후 로그인하고 accessToken 을 돌려준다. 로그인할 때 이 기기의 비회원 데이터가 계정에 연결된다. */
    public String signupAndLogin(String username, String deviceId) throws Exception {
        String credentials = "{\"username\":\"" + username + "\",\"password\":\"pw\"";
        assertThat(send("POST", "/auth/signup", credentials + "}", null, null).statusCode()).isEqualTo(200);
        return login(username, deviceId);
    }

    public String login(String username, String deviceId) throws Exception {
        HttpResponse<String> response = send(
                "POST", "/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"pw\",\"deviceId\":\"" + deviceId + "\"}",
                null, null
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).path("accessToken").asText();
    }

    /** 앱의 파일 등록(POST /files)과 같은 본문. 원본 파일은 올리지 않고 앱 내부 경로(uri)와 미리보기만 보낸다. */
    public JsonNode registerFile(String title, String folderPath, String accessToken, String deviceId) throws Exception {
        String type = title.toLowerCase().endsWith(".epub") ? "EPUB" : "TXT";
        String body = "{\"title\":\"" + title + "\",\"type\":\"" + type + "\",\"preview\":\"preview of " + title + "\","
                + "\"date\":\"2026-09-17T00:00:00Z\",\"rating\":0,"
                + "\"uri\":\"file:///data/user/0/com.readme.app/files/books/" + title + "\","
                + "\"path\":\"" + folderPath + "\",\"deviceId\":\"" + deviceId + "\"}";
        HttpResponse<String> response = send("POST", "/files", body, accessToken, deviceId);
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }

    public JsonNode listFiles(String folderPath, String accessToken, String deviceId) throws Exception {
        HttpResponse<String> response = send(
                "GET", "/files?path=" + encode(folderPath) + "&page=0&size=100&sort=date,desc", null, accessToken, deviceId
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).path("content");
    }

    public HttpResponse<String> send(String method, String path, String body, String accessToken, String deviceId)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        if (deviceId != null) {
            builder.header("X-Device-Id", deviceId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
