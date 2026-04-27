package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.FileDeleteRequest;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.ReadMe.demo.dto.FileDto;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.security.CustomUserDetails;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    // 파일 저장
    @PostMapping
    public FileEntity saveFile(
            @RequestBody FileEntity file,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        return fileService.saveFile(file, deviceId, authentication);
    }

    // 파일조회
    // GET /files?page=0&size=15&sort=date,desc
    @GetMapping
    public Page<FileDto> getFiles(
            @RequestParam(defaultValue = "root", required = false) String path,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(defaultValue = "date,desc") String sort,
            Authentication authentication
    ) {
        if (path == null || path.isEmpty()) {
            path = "root";
        }

        String userId = null;
        if (authentication != null && authentication.isAuthenticated()) {
            userId = String.valueOf(((CustomUserDetails) authentication.getPrincipal()).getUserId());
        }

        return fileService.getFilesByPath(path, deviceId, userId, page, size, sort);
    }

    // 검색 엔드포인트 추가
    @GetMapping("/search")
    public Page<FileDto> searchFiles(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "date,desc") String sort,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        if ((authentication == null)
                && (deviceId == null || deviceId.isEmpty())) {
            throw new UnauthorizedException("인증 정보 없음");
        }

        return fileService.searchFiles(keyword, page, size, sort, deviceId, authentication);
    }

    // 파일 정보 업데이트 (제목, 리뷰, 별점)
    @PatchMapping("/{id}")
    public FileEntity updateFile(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        return fileService.updateFile(id, body);
    }

    // 파일삭제 (여러개 삭제 지원)
    @DeleteMapping
    public void deleteFiles(@RequestBody FileDeleteRequest request,
                            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                            Authentication authentication) {
        fileService.deleteFiles(request.getIds(), deviceId, authentication);
    }

    // FileController.java
    // 파일 ID로 조회 (추가!)
    @GetMapping("/{id}")
    public FileEntity getFile(@PathVariable Long id) {
        return fileService.getFileById(id);
    }

    // 히스토리 조회 (프론트에서 사용)
    @GetMapping("/history")
    public List<FileEntity> getRecentFiles(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {

        if (authentication != null && authentication.isAuthenticated()) {
            Long userId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
            return fileService.getRecentFilesByUserId(userId);
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return fileService.getRecentFilesByDeviceId(deviceId);
        } else {
            return List.of(); // 빈 리스트 반환
        }
    }

    // 파일 프로그래스 저장 (이어읽기 저장)
    @PatchMapping("/{id}/progress")
    public FileEntity updateProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
       return fileService.updateProgress(id, body, deviceId, authentication);
    }

    // 파일 중복 체크
    @GetMapping("/check")
    public ResponseEntity<Map<String, Boolean>> checkDuplicate(
            @RequestParam String title,
            @RequestParam String path
    ) {
        boolean exists = fileService.isDuplicate(title, path);
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    // AI 분석 정보 조회 (장르, 키워드, 분위기, 요약, 타겟)
    // GET /files/{id}/ai-info
    @GetMapping("/{id}/ai-info")
    public ResponseEntity<AiInfoResponse> getAiInfo(
            @PathVariable Long id,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(fileService.getAiInfo(id, deviceId, authentication));
    }

    @GetMapping("/test-seed")
    public String seedTestData(@RequestParam(defaultValue = "1000") int count) {
        Random rand = new Random();
        String[] genres = {"소설", "에세이", "자기계발", "기술서", "역사", "과학"};
        String[] authors = {"김", "이", "박", "최", "정", "강", "조", "윤"};

        for (int i = 1; i <= count; i++) {
            FileEntity file = new FileEntity();
            file.setTitle(genres[rand.nextInt(genres.length)] + "_" +
                    authors[rand.nextInt(authors.length)] + "_" + i);
            file.setPath("root");
            file.setRating(rand.nextInt(5) + 1); // 1-5점
            file.setReview(i % 3 == 0 ? "재미있게 읽었습니다" : null);
            file.setDate(LocalDateTime.now().minusDays(rand.nextInt(365)));
            file.setPreview("테스트 미리보기 내용 " + i);

            fileService.saveFile(file, null, null);
        }

        return count + "개의 테스트 파일이 생성되었습니다";
    }
}
