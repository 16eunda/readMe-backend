package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.UpdateFileAiInfoRequest;
import com.ReadMe.demo.exception.FileNotFoundException;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;


@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisService {

    // 다른 곳(백그라운드 워커)이 이미 분석 중일 때 결과를 기다리는 최대 시간. AI 호출 상한(25초)보다 조금 길게 둔다.
    private static final Duration WAIT_FOR_RUNNING_ANALYSIS = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final FileRepository fileRepository;
    private final GeminiService geminiService;
    private final SubscriptionService subscriptionService;
    private final AnalysisStateService analysisState;

    // AI 분석 정보 전체 조회 (장르, 키워드, 분위기, 요약, 타겟)
    // 사용자가 직접 요청한 경우 → 동기 처리 (바로 결과 반환)
    public AiInfoResponse getAiInfo(Long fileId, String deviceId, Authentication authentication) {
        UserEntity user = extractUser(authentication);

        // 소유권 검증. 남의 파일 분석 결과를 열람할 수 없다.
        FileEntity file = findOwnedFile(fileId, user, deviceId);

        if (!subscriptionService.isPremium(user, deviceId)) {
            return AiInfoResponse.notAvailable();
        }

        // 이미 분석 완료된 경우 바로 반환 (구독이 끊겼다 다시 시작해도 저장된 결과를 그대로 쓴다)
        if ("DONE".equals(file.getAnalysisStatus()) && file.getAiGenre() != null) {
            return AiInfoResponse.from(file);
        }

        // 백그라운드 워커가 이미 이 파일을 분석 중이면 AI 를 한 번 더 부르지 않고 그 결과를 기다린다.
        // 워커가 도중에 내려놓으면(실패·한도 초과) 이 요청이 이어서 분석한다.
        long deadline = System.nanoTime() + WAIT_FOR_RUNNING_ANALYSIS.toNanos();
        while (!analysisState.claim(fileId)) {
            String status = fileRepository.findAnalysisStatusById(fileId)
                    .orElseThrow(() -> new FileNotFoundException(fileId));
            if ("DONE".equals(status)) {
                return reload(fileId);
            }
            if (System.nanoTime() > deadline) {
                log.warn("분석 완료를 기다리다 시간 초과. fileId={}", fileId);
                return AiInfoResponse.failed();
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AiInfoResponse.failed();
            }
        }

        // 여기부터는 이 요청만 분석한다. 결과는 분석 컬럼만 저장한다.
        try {
            // 같은 제목의 이미 분석된 "본인" 파일 있으면 복사 (API 호출 없음, 즉시 반환)
            FileEntity existing = fileRepository.findOwnAnalyzedSameTitle(
                    file.getNormalizedTitle(),
                    file.getId(),
                    user != null ? user.getId() : null,
                    deviceId
            );

            if (existing != null) {
                analysisState.completeByCopy(fileId, existing);
            } else {
                // 프리미엄 → Gemini 직접 호출 (동기, 일일 제한 없음)
                Map<String, String> analysis = geminiService.analyzeText(file.getPreview(), file.getTitle());
                analysisState.complete(fileId, analysis);
            }

            return reload(fileId);

        } catch (Exception e) {
            log.error("AI 분석 실패. fileId={} - {}", fileId, e.getMessage());
            // 분석 실패를 기록해 재시도 대상으로 남긴다.
            analysisState.release(fileId, "FAILED");
            return AiInfoResponse.failed();
        }
    }

    // AI 분석 정보 업데이트
    public AiInfoResponse updateFileAiInfo(Long fileId, String deviceId, Authentication authentication, UpdateFileAiInfoRequest request) {
        UserEntity user = extractUser(authentication);

        // 소유권 검증. 남의 파일 분석 결과를 수정할 수 없다.
        FileEntity file = findOwnedFile(fileId, user, deviceId);

        if (!subscriptionService.isPremium(user, deviceId)) {
            return AiInfoResponse.notAvailable();
        }

        // 업데이트된 정보 저장
        if (request.getGenre() != null) file.setAiGenre(request.getGenre());

        if (request.getKeywords() != null) {
            file.setAiKeywords(String.join(", ", request.getKeywords()));
        }

        if (request.getMood() != null) file.setAiMood(request.getMood());
        if (request.getContent() != null) file.setAiContent(request.getContent());
        if (request.getSummary() != null) file.setAiSummary(request.getSummary());
        if (request.getTarget() != null) file.setAiTarget(request.getTarget());

        // 수동 업데이트는 분석 상태를 변경하지 않음 (사용자가 직접 수정한 경우도 있으므로)
        fileRepository.save(file);

        return AiInfoResponse.from(file);
    }

    /** 요청자가 실제로 소유한 파일만 돌려준다. */
    private FileEntity findOwnedFile(Long fileId, UserEntity user, String deviceId) {
        if (user != null) {
            return fileRepository.findByIdAndUserId(fileId, user.getId())
                    .orElseThrow(() -> new FileNotFoundException(fileId));
        }
        if (deviceId != null && !deviceId.isBlank()) {
            return fileRepository.findByIdAndDeviceIdAndUserIsNull(fileId, deviceId)
                    .orElseThrow(() -> new FileNotFoundException(fileId));
        }
        throw new UnauthorizedException("인증 정보 없음");
    }

    private AiInfoResponse reload(Long fileId) {
        return fileRepository.findAiInfoById(fileId).orElseThrow(() -> new FileNotFoundException(fileId));
    }

    private UserEntity extractUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUser();
        }
        return null;
    }
}
