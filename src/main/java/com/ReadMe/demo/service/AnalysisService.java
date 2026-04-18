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

    // 하루 최대 AI 분석 횟수
    private static final int DAILY_LIMIT = 10;

    /**
     * 큐에서 호출되는 분석 메서드
     * - 프리미엄 체크
     * - 일일 제한 체크
     * - 분석 실행 + 로그 기록
     */
    public void analyze(Long fileId) {
        fileRepository.findById(fileId).ifPresent(file -> {

            // 이미 분석 완료된 경우 스킵
            if ("DONE".equals(file.getAnalysisStatus())) {
                log.info("⏭️ 이미 분석 완료: {}", file.getTitle());
                return;
            }

            // 프리미엄 체크
            UserEntity user = file.getUser();
            String deviceId = file.getDeviceId();

            if (!subscriptionService.isPremium(user, deviceId)) {
                log.info("🚫 비프리미엄 유저 - AI 분석 스킵: {}", file.getTitle());
                file.setAnalysisStatus("PENDING");
                fileRepository.save(file);
                return;
            }

            // 일일 제한 체크
            if (!canAnalyzeToday(user, deviceId)) {
                log.info("🚫 오늘 AI 분석 한도 초과 ({}/{}): {}",
                        DAILY_LIMIT, DAILY_LIMIT, file.getTitle());
                file.setAnalysisStatus("LIMIT_EXCEEDED");
                fileRepository.save(file);
                return;
            }

            // 같은 제목의 이미 분석된 파일 있으면 복사 (API 호출 없음, 횟수 차감 없음)
            FileEntity existing = fileRepository
                    .findFirstByNormalizedTitleAndAiGenreIsNotNullAndIdNot(
                            file.getNormalizedTitle(), file.getId());

            if (existing != null) {
                copyAnalysis(file, existing);
                fileRepository.save(file);
                log.info("♻️ 기존 분석 복사 (횟수 차감 없음): {}", file.getTitle());
                return;
            }

            // AI 분석 실행
            try {
                file.setAnalysisStatus("PROCESSING");
                fileRepository.save(file);

                Map<String, String> analysis =
                        geminiService.analyzeText(file.getPreview(), file.getTitle());

                file.setAiGenre(analysis.get("genre"));
                file.setAiKeywords(analysis.get("keywords"));
                file.setAiMood(analysis.get("mood"));
                file.setAiContent(analysis.get("info"));
                file.setAiSummary(analysis.get("summary"));
                file.setAiTarget(analysis.get("target"));
                file.setAiAnalyzedAt(LocalDateTime.now());
                file.setAnalysisStatus("DONE");

                // 사용 로그 기록 (API 실제 호출한 경우만)
                recordAnalysisLog(user, deviceId, fileId);
                log.info("✅ AI 분석 완료: {} → 장르: {}", file.getTitle(), analysis.get("genre"));

            } catch (Exception e) {
                file.setAnalysisStatus("FAILED");
                log.error("❌ AI 분석 실패: {} - {}", file.getTitle(), e.getMessage());
            }

            fileRepository.save(file);
        });
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

    /**
     * 기존 분석 결과 복사
     */
    private void copyAnalysis(FileEntity target, FileEntity source) {
        target.setAiGenre(source.getAiGenre());
        target.setAiKeywords(source.getAiKeywords());
        target.setAiMood(source.getAiMood());
        target.setAiContent(source.getAiContent());
        target.setAiSummary(source.getAiSummary());
        target.setAiTarget(source.getAiTarget());
        target.setAiAnalyzedAt(LocalDateTime.now());
        target.setAnalysisStatus("DONE");
    }
}