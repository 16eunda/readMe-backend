package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.AiAnalysisLog;
import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.repository.AiAnalysisLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisService {

    private final FileRepository fileRepository;
    private final GeminiService geminiService;
    private final SubscriptionService subscriptionService;
    private final AiAnalysisLogRepository analysisLogRepository;
    private final AnalysisStateService analysisState;

    // 하루 최대 AI 분석 횟수
    private static final int DAILY_LIMIT = 10;

    /**
     * 큐에서 호출되는 분석 메서드
     * - 한 곳만 분석하도록 먼저 "분석 중"으로 선점
     * - 프리미엄 체크
     * - 일일 제한 체크 (bypassLimit=true면 스킵 → getAiInfo 직접 요청용)
     * - 분석 실행 + 로그 기록
     */
    public void analyze(Long fileId, boolean bypassLimit) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null) {
            return;
        }

        // 이미 분석 완료된 경우 스킵
        if ("DONE".equals(file.getAnalysisStatus())) {
            log.info("⏭️ 이미 분석 완료: {}", file.getTitle());
            return;
        }

        // 같은 파일이 큐에 다시 들어왔거나, 사용자가 AI 정보 화면을 열어 이미 분석 중이면 여기서 멈춘다.
        if (!analysisState.claim(fileId)) {
            log.info("⏭️ 이미 다른 곳에서 분석 중: {}", file.getTitle());
            return;
        }

        UserEntity user = file.getUser();
        String deviceId = file.getDeviceId();

        // 선점한 뒤에는 어떤 경우든 DONE 또는 재시도 가능한 상태로 끝낸다. (PROCESSING 으로 남기지 않는다)
        try {
            if (!subscriptionService.isPremium(user, deviceId)) {
                log.info("🚫 비프리미엄 유저 - AI 분석 스킵: {}", file.getTitle());
                analysisState.release(fileId, "PENDING");
                return;
            }

            // 일일 제한 체크 (파일 추가 시 자동 분석에만 적용, getAiInfo 직접 요청은 제외)
            // 한도를 넘은 파일은 AnalysisBacklogScheduler 가 다음 날 다시 큐에 넣는다.
            if (!bypassLimit && !canAnalyzeToday(user, deviceId)) {
                log.info("🚫 오늘 AI 분석 한도 초과 ({}/{}): {}",
                        DAILY_LIMIT, DAILY_LIMIT, file.getTitle());
                analysisState.release(fileId, "LIMIT_EXCEEDED");
                return;
            }

            // 같은 제목의 이미 분석된 "본인" 파일 있으면 복사 (API 호출 없음, 횟수 차감 없음)
            FileEntity existing = fileRepository.findOwnAnalyzedSameTitle(
                    file.getNormalizedTitle(),
                    file.getId(),
                    user != null ? user.getId() : null,
                    deviceId
            );

            if (existing != null) {
                analysisState.completeByCopy(fileId, existing);
                log.info("기존 분석 복사 (횟수 차감 없음): {}", file.getTitle());
                return;
            }

            // AI 분석 실행. 결과는 분석 컬럼만 저장해서, 기다리는 동안 앱이 저장한 읽던 위치 등을 되돌리지 않는다.
            Map<String, String> analysis =
                    geminiService.analyzeText(file.getPreview(), file.getTitle());
            analysisState.complete(fileId, analysis);

            // 사용 로그 기록 (API 실제 호출한 경우만)
            recordAnalysisLog(user, deviceId, fileId);
            log.info("AI 분석 완료: {} -> 장르: {}", file.getTitle(), analysis.get("genre"));

        } catch (Exception e) {
            log.error("AI 분석 실패: {} - {}", file.getTitle(), e.getMessage());
            analysisState.release(fileId, "FAILED");
        }
    }

    // 기존 호환용 (워커에서 파일 추가 자동 분석 → 일일 제한 적용)
    public void analyze(Long fileId) {
        analyze(fileId, false);
    }

    /**
     * 오늘 분석 가능한지 체크
     */
    public boolean canAnalyzeToday(UserEntity user, String deviceId) {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        long todayCount;

        if (user != null) {
            todayCount = analysisLogRepository.countTodayByUserId(user.getId(), startOfDay);
        } else if (deviceId != null) {
            todayCount = analysisLogRepository.countTodayByDeviceId(deviceId, startOfDay);
        } else {
            return false;
        }

        return todayCount < DAILY_LIMIT;
    }

    /**
     * 오늘 남은 분석 횟수
     */
    public long getRemainingToday(UserEntity user, String deviceId) {
        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
        long todayCount;

        if (user != null) {
            todayCount = analysisLogRepository.countTodayByUserId(user.getId(), startOfDay);
        } else if (deviceId != null) {
            todayCount = analysisLogRepository.countTodayByDeviceId(deviceId, startOfDay);
        } else {
            return 0;
        }

        return Math.max(0, DAILY_LIMIT - todayCount);
    }

    /**
     * 분석 사용 로그 기록
     */
    private void recordAnalysisLog(UserEntity user, String deviceId, Long fileId) {
        AiAnalysisLog logEntry = AiAnalysisLog.builder()
                .user(user)
                .deviceId(deviceId)
                .fileId(fileId)
                .analyzedAt(LocalDateTime.now())
                .build();
        analysisLogRepository.save(logEntry);
    }
}
