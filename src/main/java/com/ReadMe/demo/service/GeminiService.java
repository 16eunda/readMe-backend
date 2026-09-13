package com.ReadMe.demo.service;

import com.ReadMe.demo.exception.AiAnalysisFailedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    // FileEntity 의 컬럼 길이와 맞춘다. 초과하면 저장 시점에 예외가 나서 파일이 PROCESSING 에 고착된다.
    private static final int MAX_GENRE = 255;
    private static final int MAX_MOOD = 500;
    private static final int MAX_KEYWORDS = 500;
    private static final int MAX_TARGET = 500;
    private static final int MAX_SUMMARY = 2000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @throws AiAnalysisFailedException 분석에 실패한 경우. 호출부는 FAILED 로 기록하고 재시도 대상으로 남긴다.
     */
    public Map<String, String> analyzeText(String content, String title) {
        // 텍스트가 없으면 빈 문자열, 있으면 앞부분만 사용 (약 2000자)
        String textToAnalyze = "";
        if (content != null && !content.isEmpty()) {
            textToAnalyze = content.length() > 2000
                    ? content.substring(0, 2000)
                    : content;
        }

        log.info("분석 시작 - 제목: '{}', 텍스트 길이: {}자", title, textToAnalyze.length());

        String prompt = buildPrompt(title, textToAnalyze);

        HttpResponse<String> response;
        try {
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> contents = new HashMap<>();
            Map<String, String> parts = new HashMap<>();
            parts.put("text", prompt);
            contents.put("parts", new Object[]{parts});
            requestBody.put("contents", new Object[]{contents});

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "?key=" + apiKey))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiAnalysisFailedException("AI 분석이 중단되었습니다.", e);
        } catch (Exception e) {
            // 타임아웃 포함. 스택트레이스는 남기되 프롬프트(=사용자 파일 내용)는 남기지 않는다.
            log.error("Gemini API 호출 실패: {}", e.getMessage());
            throw new AiAnalysisFailedException("Gemini API 호출에 실패했습니다.", e);
        }

        if (response.statusCode() != 200) {
            log.error("Gemini API 오류 ({})", response.statusCode());
            throw new AiAnalysisFailedException("Gemini API가 " + response.statusCode() + " 를 반환했습니다.");
        }

        return parseAnalysis(response.body());
    }

    private String buildPrompt(String title, String textToAnalyze) {
        // 근거가 없을 때 값을 지어내지 않도록 명시한다.
        // (이전 프롬프트는 "약 500자 설명 작성" 처럼 분량을 요구해서 hallucination 을 유도했다.)
        return """
                너는 책 분류 도우미다. 아래에 주어진 "작품 이름"과 "텍스트"에서 실제로 확인되는 내용만으로 분류한다.
                규칙:
                - 주어진 텍스트에 근거가 없으면 그 항목은 빈 문자열("")로 둔다. 절대 추측해서 채우지 않는다.
                - 텍스트가 비어 있으면 제목만으로 줄거리나 설명을 지어내지 말고 summary 와 info 를 빈 문자열로 둔다.
                - 실존하는 작품이라고 확신할 수 없으면 관련 정보를 만들어내지 않는다.
                - 코드블록 없이 순수 JSON만 반환한다. 다른 설명은 하지 않는다.

                형식 예시(값의 형태를 보여주기 위한 것이며 내용은 참고하지 않는다):
                {
                  "genre": "장르 (로맨스, 판타지, 스릴러, 성장, SF 등 한 단어)",
                  "keywords": "키워드1,키워드2,키워드3 (쉼표로 구분, 최대 5개)",
                  "mood": "분위기 (감성적, 긴장감, 유쾌함 등, 쉼표로 구분)",
                  "summary": "텍스트에서 확인되는 한 줄 요약 (50자 이내, 근거 없으면 \\"\\")",
                  "info": "텍스트에서 확인되는 내용만으로 쓴 설명 (근거 없으면 \\"\\")",
                  "target": "이 책을 추천할 독자 유형 (근거 없으면 \\"\\")"
                }

                작품 이름: %s
                텍스트:
                %s
                """.formatted(title, textToAnalyze);
    }

    private Map<String, String> parseAnalysis(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new AiAnalysisFailedException("Gemini 응답에 결과가 없습니다.");
            }

            String aiResponse = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText("");

            int start = aiResponse.indexOf('{');
            int end = aiResponse.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new AiAnalysisFailedException("Gemini 응답이 JSON 형식이 아닙니다.");
            }

            JsonNode analysis = objectMapper.readTree(aiResponse.substring(start, end + 1));

            Map<String, String> result = new HashMap<>();
            result.put("genre", truncate(analysis.path("genre").asText(""), MAX_GENRE));
            result.put("keywords", truncate(analysis.path("keywords").asText(""), MAX_KEYWORDS));
            result.put("mood", truncate(analysis.path("mood").asText(""), MAX_MOOD));
            result.put("summary", truncate(analysis.path("summary").asText(""), MAX_SUMMARY));
            result.put("info", analysis.path("info").asText(""));
            result.put("target", truncate(analysis.path("target").asText(""), MAX_TARGET));

            // 장르가 비면 추천 로직이 이 파일을 아예 집지 못하므로 최소값을 채운다.
            if (result.get("genre").isBlank()) {
                result.put("genre", "미분류");
            }

            log.info("분석 결과 - 장르: {}", result.get("genre"));
            return result;

        } catch (AiAnalysisFailedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Gemini 응답 파싱 실패: {}", e.getMessage());
            throw new AiAnalysisFailedException("Gemini 응답을 해석할 수 없습니다.", e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
