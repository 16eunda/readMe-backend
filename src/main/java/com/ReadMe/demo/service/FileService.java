package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.FileReadLog;
import com.ReadMe.demo.domain.FileType;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.FileDto;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final FileReadLogRepository readLogRepository;
    private final GeminiService geminiService;
    private final QueueService queueService;
    private final SubscriptionService subscriptionService;

    // 제목 정규화 (확장자 제거)
    // "MyBook.epub" -> "MyBook"
    private String normalizeTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("\\.txt|\\.epub|\\.pdf", "").trim();
    }

    // 파일 저장
    // 파일 저장과 동시에 AI 분석도 트리거 (중복 체크 포함)
    public FileEntity saveFile(FileEntity file, String deviceId, Authentication authentication) {
        System.out.println("=== Received From RN ===");
        System.out.println(file);
        String title = file.getTitle();
        // 정규화된 제목 설정
        String normalized = normalizeTitle(title);
        file.setNormalizedTitle(normalized);

        // 파일 타입 저장
        String ext = title.substring(title.lastIndexOf('.') + 1);

        if (ext.equalsIgnoreCase("txt")) {
            file.setType(FileType.TXT);
        } else if (ext.equalsIgnoreCase("epub")) {
            file.setType(FileType.EPUB);
        }

        // 완료 여부 초기값 false
        file.setCompleted(false);

        // 일단 파일 정보 먼저 저장 (AI 분석 결과는 나중에 업데이트)
        FileEntity saved = fileRepository.save(file);
        saved.setDeviceId(deviceId);

        // 로그인 상태면 userId도 저
        if (authentication != null && authentication.isAuthenticated()) {
            UserEntity user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
            saved.setUser(user);
        }

        // 중복 체크: 같은 normalizedTitle로 이미 분석된 책이 있는지
        FileEntity existing = fileRepository.findFirstByNormalizedTitleAndAiGenreIsNotNullAndIdNot(normalized, saved.getId());

        if (existing != null) {
            // 기존 AI 결과 복사 (무료! API 호출 없음)
            saved.setAiGenre(existing.getAiGenre());
            saved.setAiKeywords(existing.getAiKeywords());
            saved.setAiMood(existing.getAiMood());
            saved.setAiSummary(existing.getAiSummary());
            saved.setAiTarget(existing.getAiTarget());
            saved.setAiAnalyzedAt(LocalDateTime.now());
            saved.setAnalysisStatus("DONE");
            System.out.println("♻️ 기존 AI 분석 결과 복사 완료: " + normalized);
        } else {
            // 프리미엄 유저면 큐에 분석 요청
            if (subscriptionService.isPremium(saved.getUser(), deviceId)) {
                saved.setAnalysisStatus("QUEUED");
                fileRepository.save(saved);
                queueService.enqueue(saved.getId());
                System.out.println("🤖 프리미엄 유저 → AI 분석 큐 등록: " + normalized);
            } else {
                // 비프리미엄 → 분석 대기 (나중에 프리미엄 되면 분석)
                saved.setAnalysisStatus("PENDING");
                System.out.println("⏸️ 비프리미엄 → AI 분석 대기: " + normalized);
            }
        }

        saved = fileRepository.save(saved);

        return saved;
    }

    // 파일조회
    // 경로로 조회 (로그인/게스트 모두 지원, 페이징/정렬)
    public Page<FileDto> getFilesByPath(String path, String deviceId, String userId, int page, int size, String sort) {
        String[] sortParams = sort.split(",");
        String property = sortParams[0];

        Sort.Direction direction = Sort.Direction.DESC;
        if (sortParams.length > 1 && sortParams[1].equalsIgnoreCase("asc")) {
            direction = Sort.Direction.ASC;
        }

        Sort sortObj;
        if (property.equals("rating")) {
            Sort.Order ratingOrder = direction == Sort.Direction.ASC
                    ? Sort.Order.asc("rating")
                    : Sort.Order.desc("rating");
            sortObj = Sort.by(ratingOrder, Sort.Order.desc("date"));
        } else {
            sortObj = Sort.by(direction, property);
        }

        Pageable pageable = PageRequest.of(page, size, sortObj);

        // userId가 있으면 userId로 조회 (로그인 상태)
        if (userId != null && !userId.isEmpty()) {
            return fileRepository.findByPathAndUserId(path, userId, pageable).map(FileDto::from);
        } else {
            // userId 없으면 deviceId로 조회 (게스트 상태)
            return fileRepository.findByPathAndDeviceIdAndUserIsNull(
                    path, deviceId, pageable
            ).map(FileDto::from);
        }
    }

    // 파일 검색
    // 사용자가 입력한 키워드가 제목에 포함된 파일을 검색 (로그인/게스트 모두 지원)
    public Page<FileDto> searchFiles(String keyword, int page, int size, String sort, String deviceId, Authentication authentication) {
        String[] sortParams = sort.split(",");
        String property = sortParams[0];
        Sort.Direction direction = Sort.Direction.DESC;
        if (sortParams.length > 1 && sortParams[1].equalsIgnoreCase("asc")) {
            direction = Sort.Direction.ASC;
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, property));

        UserEntity user = null;

        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {

            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }

        if (user != null) {
            // 로그인 상태: userId로 검색
            return fileRepository.findByUserAndTitleContainingIgnoreCase(keyword, user, pageable)
                    .map(FileDto::from);
        } else if (deviceId != null && !deviceId.isEmpty()) {
            // 게스트 상태: deviceId로 검색
            return fileRepository.findByDeviceIdAndUserIsNullAndTitleContainingIgnoreCase(keyword, deviceId, pageable)
                    .map(FileDto::from);
        }

        return Page.empty(pageable);
    }

    // 파일 정보 업데이트 (제목, 리뷰, 별점, 경로)
    public FileEntity updateFile(Long id, Map<String, Object> body) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다"));

        if (body.containsKey("title")) {
            file.setTitle((String) body.get("title"));
        }
        if (body.containsKey("review")) {
            file.setReview((String) body.get("review"));
        }
        if (body.containsKey("rating")) {
            file.setRating(((Number) body.get("rating")).intValue());
        }
        if (body.containsKey("path")) {
            file.setPath((String) body.get("path"));
        }

        return fileRepository.save(file);
    }

    // 파일삭제
    @Transactional
    public void deleteFiles(List<Long> ids, String deviceId, Authentication authentication) {

        UserEntity user = null;

        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {

            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }

        // 로그인 상태면 userId로 삭제, 게스트 상태면 deviceId로 삭제
        if (user != null) {
            fileRepository.deleteByUserAndIdIn(user, ids);
        } else {
            fileRepository.deleteByDeviceIdAndUserIsNullAndIdIn(deviceId, ids);
        }
    }

    // FileService.java
    // 파일 ID로 조회 (추가!)
    public FileEntity getFileById(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));
    }

    // 파일 프로그래스 저장
    public FileEntity updateProgress(Long id, Map<String, Object> body, String deviceId, Authentication authentication) {
        System.out.println("📥 받은 body: " + body);
        System.out.println("🔍 recordReadLog 값: " + body.get("recordReadLog"));

        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));

        // 완독여부
        boolean completed = false;

        if (body.containsKey("progress")) {
            Number p = (Number) body.get("progress");
            completed = p.doubleValue() >= 0.99; // 99% 이상이면 완독으로 간주
            file.setProgress(p.doubleValue());
        }

        if (body.containsKey("epubCfi")) {
            file.setEpubCfi((String) body.get("epubCfi"));
        }

        // 완독 여부 업데이트
        file.setCompleted(completed);

        // 읽은 시점 기록
        file.setLastReadAt(LocalDateTime.now());

        // 👇 미분석 파일이면 프리미엄 유저일 때 큐에 분석 요청
        if (!"DONE".equals(file.getAnalysisStatus()) && !"QUEUED".equals(file.getAnalysisStatus())
                && !"PROCESSING".equals(file.getAnalysisStatus())) {

            UserEntity user = null;
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof CustomUserDetails) {
                user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
            }

            if (subscriptionService.isPremium(user, deviceId)) {
                file.setAnalysisStatus("QUEUED");
                queueService.enqueue(file.getId());
                System.out.println("🤖 책 읽는 중 → 미분석 파일 큐 등록: " + file.getTitle());
            }
        }

        // 👇 읽기 로그 기록 (같은 날은 1회만)
        if (body.containsKey("recordReadLog") && Boolean.TRUE.equals(body.get("recordReadLog"))) {
            LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
            LocalDateTime startOfNextDay = startOfDay.plusDays(1);

            Optional<FileReadLog> existingLog = readLogRepository.findByFileIdAndToday(id, startOfDay, startOfNextDay);

            if (existingLog.isEmpty()) {
                FileReadLog log = new FileReadLog();
                log.setFile(file);
                log.setReadAt(LocalDateTime.now());
                readLogRepository.save(log);
                System.out.println("📝 새로운 로그 생성 중...");
            } else {
                System.out.println("⏭️ 오늘 이미 로그 있음, 스킵");
            }
            // 이미 오늘 로그가 있으면 아무것도 안 함
        }else {
            System.out.println("❌ 로그 기록 조건 불충족");
        }

        return fileRepository.save(file);
    }

    // 중복 여부 판단
    public boolean isDuplicate(String title, String path) {
        return fileRepository.existsByTitleAndPath(title, path);
    }

    // 최근 읽은 파일 조회 (히스토리)
    public List<FileEntity> getRecentFilesByUserId(Long userId) {
        return fileRepository
                .findTop50ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(userId);
    }

    // 최근 읽은 파일 조회 (히스토리, 게스트용)
    public List<FileEntity> getRecentFilesByDeviceId(String deviceId) {
        return fileRepository
                .findTop50ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(deviceId);
    }


    // AI 분석 정보 전체 조회 (장르, 키워드, 분위기, 요약, 타겟)
    // 분석 완료면 바로 반환, 미분석이면 큐에 넣고 상태 반환
    public AiInfoResponse getAiInfo(Long fileId, String deviceId, Authentication authentication) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileId));

        // 이미 분석 완료된 경우 바로 반환
        if ("DONE".equals(file.getAnalysisStatus()) && file.getAiGenre() != null) {
            return AiInfoResponse.from(file);
        }

        // 같은 제목의 이미 분석된 파일 있으면 복사 (API 호출 없음)
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

        // 미분석 → 프리미엄이면 큐에 넣기
        UserEntity user = null;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }

        if (subscriptionService.isPremium(user, deviceId)) {
            if (!"QUEUED".equals(file.getAnalysisStatus()) && !"PROCESSING".equals(file.getAnalysisStatus())) {
                file.setAnalysisStatus("QUEUED");
                fileRepository.save(file);
                queueService.enqueue(file.getId());
            }
            return AiInfoResponse.analyzing(); // "분석 중" 상태 반환
        }

        // 비프리미엄
        return AiInfoResponse.notAvailable(); // "프리미엄 필요" 상태 반환
    }
}
