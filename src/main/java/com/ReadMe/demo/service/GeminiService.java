package com.ReadMe.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, String> analyzeText(String content, String title) {
        try {
            // 텍스트가 없으면 빈 문자열, 있으면 앞부분만 사용 (약 2000자)
            String textToAnalyze = "";
            if (content != null && !content.isEmpty()) {
                textToAnalyze = content.length() > 2000
                        ? content.substring(0, 2000)
                        : content;
            }

            log.info("📘 분석 시작 - 제목: '{}', 텍스트 길이: {}자", title, textToAnalyze.length());
            log.info("🌐 API URL: {}", apiUrl);
            log.info("🔑 API KEY 앞 10자리: {}", apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) : "NULL");

            String prompt = String.format(
                    "다음 작품 이름, 텍스트(책 내용)을 보고 분석, 조사 해서 예시 형식을 참고해서 답변 JSON 형식으로만 답해줘. 다른 설명은 절대 하지 마. 코드블록 없이 순수 JSON만 반환해:\n"
                    + "예시 : " +
                            " genre : 현대물, 로맨스" +
                            " keywords : 사내연애, 후회남, 무심남, 털털녀, 순진녀, 후회물, 소유욕" +
                            " mood : 감정적, 세드" +
                            " summary : 사랑을 모르던 남자의 집착" +
                            " info : 갑작스런 고백으로 인해 퇴사를 고민하던 어느 날, 희건은 수아에게 연애를 제안한다. \n 수아를 좋아하는 것은" +
                            " 희건이지만, 수아는 희건이 자신을 좋아하는 줄도 모르고, 희건의 제안을 받아들인다. \n 그렇게 시작된 두 사람의 연애는 예상치 못한 방향으로 흘러가는데... \n" +
                            " target : 피폐물 좋아하는 독자" +
                            " 답변 json 형식 : " +
                            "{\n" +
                            "  \"genre\": \"장르 (로맨스, 판타지, 스릴러, 성장, SF 등 한 단어)\",\n" +
                            "  \"keywords\": \"키워드1,키워드2,키워드3 (쉼표로 구분, 최대 5개)\",\n" +
                            "  \"mood\": \"분위기 (감성적, 긴장감, 유쾌함 등, 쉼표로 구분)\",\n" +
                            "  \"summary\": \"한 줄 요약 (50자 이내)\",\n" +
                            "  \"info\": \"텍스트를 참고해서 적절한 책 설명을 작성(약 500자)\",\n" +
                            "  \"target\": \"이 책을 추천할 독자 유형 (예: 피폐 로맨스를 좋아하는 20대 독자)\"\n" +
                            "}\n\n" +
                            "작품 이름: %s\n" +
                            "텍스트:\n%s",
                    title,
                    textToAnalyze
            );

            // Gemini API 요청 본문
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> contents = new HashMap<>();
            Map<String, String> parts = new HashMap<>();
            parts.put("text", prompt);
            contents.put("parts", new Object[]{parts});
            requestBody.put("contents", new Object[]{contents});

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            // HTTP 요청
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "?key=" + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.info("🤖 Gemini API 호출 시작...");

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("📡 HTTP 상태코드: {}", response.statusCode());
            log.info("📄 응답 body (앞 500자): {}", response.body().substring(0, Math.min(500, response.body().length())));

            if (response.statusCode() != 200) {
                log.error("❌ Gemini API 오류 ({}): {}", response.statusCode(), response.body());
                return getDefaultAnalysis();
            }

            // 응답 파싱
            JsonNode root = objectMapper.readTree(response.body());
            String aiResponse = root.path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            log.info("✅ AI 응답: {}", aiResponse);

            // JSON 추출 - Gemini 응답 형태가 다양함
            // 1. 순수 JSON
            // 2. ```json { } ```
            // 3. ``` { } ```
            String jsonStr = aiResponse.trim();
            if (jsonStr.contains("{")) {
                jsonStr = jsonStr.substring(
                        jsonStr.indexOf("{"),
                        jsonStr.lastIndexOf("}") + 1
                );
            } else {
                log.error("❌ JSON 형식 응답 없음: {}", aiResponse);
                return getDefaultAnalysis();
            }

            JsonNode analysis = objectMapper.readTree(jsonStr);

            Map<String, String> result = new HashMap<>();
            result.put("genre", analysis.path("genre").asText("기타"));
            result.put("keywords", analysis.path("keywords").asText(""));
            result.put("mood", analysis.path("mood").asText(""));
            result.put("summary", analysis.path("summary").asText(""));
            result.put("info", analysis.path("info").asText(""));
            result.put("target", analysis.path("target").asText(""));

            log.info("📊 분석 결과 - 장르: {}, 키워드: {}", result.get("genre"), result.get("keywords"));

            return result;

        } catch (Exception e) {
            log.error("❌ Gemini API 호출 실패: ", e);
            return getDefaultAnalysis();
        }
    }

    private Map<String, String> getDefaultAnalysis() {
        Map<String, String> result = new HashMap<>();
        result.put("genre", "미분류");
        result.put("keywords", "");
        result.put("mood", "");
        result.put("summary", "");
        result.put("target", "");
        return result;
    }
}