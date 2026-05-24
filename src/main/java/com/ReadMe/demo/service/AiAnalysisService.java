package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.UpdateFileAiInfoRequest;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final FileRepository fileRepository;
    private final GeminiService geminiService;
    private final SubscriptionService subscriptionService;

    // AI 분석 정보 전체 조회 (장르, 키워드, 분위기, 요약, 타겟)
    // 사용자가 직접 요청한 경우 → 동기 처리 (바로 결과 반환)
    public AiInfoResponse getAiInfo(Long fileId, String deviceId, Authentication authentication) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileId));

        // 이미 분석 완료된 경우 바로 반환
        if ("DONE".equals(file.getAnalysisStatus()) && file.getAiGenre() != null) {
            return AiInfoResponse.from(file);
        }

        // 같은 제목의 이미 분석된 파일 있으면 복사 (API 호출 없음, 즉시 반환)
        FileEntity existing = fileRepository
                .findFirstByNormalizedTitleAndAiGenreIsNotNullAndIdNot(
                        file.getNormalizedTitle(), file.getId());

        if (existing != null) {
            file.setAiGenre(existing.getAiGenre());
            file.setAiKeywords(existing.getAiKeywords());
            file.setAiMood(existing.getAiMood());
            file.setAiSummary(existing.getAiSummary());
            file.setAiTarget(existing.getAiTarget());
            file.setAiAnalyzedAt(LocalDateTime.now());
            file.setAnalysisStatus("DONE");
            fileRepository.save(file);
            return AiInfoResponse.from(file);
        }

        // 프리미엄 체크
        UserEntity user = null;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }

        if (!subscriptionService.isPremium(user, deviceId)) {
            return AiInfoResponse.notAvailable();
        }

        // 프리미엄 → Gemini 직접 호출 (동기, 일일 제한 없음)
        try {
            file.setAnalysisStatus("PROCESSING");
            fileRepository.save(file);

            Map<String, String> analysis = geminiService.analyzeText(file.getPreview(), file.getTitle());

            file.setAiGenre(analysis.get("genre"));
            file.setAiKeywords(analysis.get("keywords"));
            file.setAiMood(analysis.get("mood"));
            file.setAiContent(analysis.get("info"));
            file.setAiSummary(analysis.get("summary"));
            file.setAiTarget(analysis.get("target"));
            file.setAiAnalyzedAt(LocalDateTime.now());
            file.setAnalysisStatus("DONE");
            fileRepository.save(file);

            return AiInfoResponse.from(file);

        } catch (Exception e) {
            file.setAnalysisStatus("FAILED");
            fileRepository.save(file);
            throw new RuntimeException("AI 분석 실패: " + e.getMessage());
        }
    }

    // AI 분석 정보 업데이트
    public AiInfoResponse updateFileAiInfo(Long fileId, String deviceId, Authentication authentication, UpdateFileAiInfoRequest request) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileId));

        // 프리미엄 체크
        UserEntity user = null;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }

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
}
