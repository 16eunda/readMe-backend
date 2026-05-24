package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.UpdateFileAiInfoRequest;
import com.ReadMe.demo.service.AiAnalysisService;
import com.ReadMe.demo.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("files")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    // AI 분석 정보 조회 (장르, 키워드, 분위기, 요약, 타겟)
    // GET /files/{id}/ai-info
    @GetMapping("/{id}/ai-info")
    public ResponseEntity<AiInfoResponse> getAiInfo(
            @PathVariable Long id,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(aiAnalysisService.getAiInfo(id, deviceId, authentication));
    }

    // AI 분석 정보 업데이트 (장르, 키워드, 분위기, 요약, 타겟) - 프리미엄 사용자만
    @PatchMapping("/{id}/ai-info")
    public ResponseEntity<AiInfoResponse> fetchAiInfo(
            @PathVariable Long id,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication,
            @RequestBody UpdateFileAiInfoRequest request
    ) {
        return ResponseEntity.ok(aiAnalysisService.updateFileAiInfo(id, deviceId, authentication, request));
    }
}
