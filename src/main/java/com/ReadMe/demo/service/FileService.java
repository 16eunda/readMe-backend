package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.domain.FileReadLog;
import com.ReadMe.demo.domain.FileType;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.FileDto;
import com.ReadMe.demo.dto.FileLocationResponse;
import com.ReadMe.demo.dto.HistoryFileDto;
import com.ReadMe.demo.exception.FileNotFoundException;
import com.ReadMe.demo.exception.UnauthorizedException;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
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
import java.util.logging.Logger;

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
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("X-Device-Id 헤더가 필요합니다.");
        }

        String title = file.getTitle();
        if (title == null || title.isBlank() || file.getPath() == null || file.getPath().isBlank()) {
            throw new IllegalArgumentException("파일 제목과 경로가 필요합니다.");
        }
        // 정규화된 제목 설정
        String normalized = normalizeTitle(title);
        file.setNormalizedTitle(normalized);

        // 파일 타입 저장
        String ext = title.substring(title.lastIndexOf('.') + 1);

        if (ext.equalsIgnoreCase("txt")) {
            file.setType(FileType.TXT);
        } else if (ext.equalsIgnoreCase("epub")) {
            file.setType(FileType.EPUB);
        } else {
            throw new IllegalArgumentException("txt 또는 epub 파일만 등록할 수 있습니다.");
        }

        // AI 후처리가 실패해도 소유권과 기본 상태가 완성된 파일은 남긴다.
        file.setCompleted(false);
        file.setDeviceId(deviceId);
        file.setAnalysisStatus("PENDING");

        // 로그인 상태면 userId도 저장
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            UserEntity user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
            file.setUser(user);
        }

        // 완성된 기본 상태로 먼저 등록한다. 중복 여부는 /files/check에서 안내만 한다.
        FileEntity saved = fileRepository.saveAndFlush(file);

        try {
            // 같은 제목의 기존 분석 결과는 사용자/기기와 무관하게 재사용한다.
            FileEntity existing = fileRepository.findFirstByNormalizedTitleAndAiGenreIsNotNullAndIdNot(
                    normalized, saved.getId()
            );

            if (existing != null) {
                saved.setAiGenre(existing.getAiGenre());
                saved.setAiKeywords(existing.getAiKeywords());
                saved.setAiMood(existing.getAiMood());
                saved.setAiSummary(existing.getAiSummary());
                saved.setAiTarget(existing.getAiTarget());
                saved.setAiAnalyzedAt(LocalDateTime.now());
                saved.setAnalysisStatus("DONE");
                System.out.println("♻️ 기존 AI 분석 결과 복사 완료: " + normalized);
            } else if (subscriptionService.isPremium(saved.getUser(), deviceId)) {
                saved.setAnalysisStatus("QUEUED");
                fileRepository.save(saved);
                queueService.enqueue(saved.getId());
                System.out.println("🤖 프리미엄 유저 → AI 분석 큐 등록: " + normalized);
            } else {
                System.out.println("⏸️ 비프리미엄 → AI 분석 대기: " + normalized);
            }
        } catch (RuntimeException e) {
            saved.setAnalysisStatus("FAILED");
            System.out.println("❌ 파일 등록 후 AI 후처리 실패: " + e.getMessage());
        }

        return fileRepository.save(saved);
    }

    // 파일조회
    // 경로로 조회 (로그인/게스트 모두 지원, 페이징/정렬)
    public Page<FileDto> getFilesByPath(String path, String deviceId, String userId, int page, int size, String sort) {
        SortSpec sortSpec = parseSort(sort);
        Pageable pageable = PageRequest.of(page, size, sortSpec.toSort());

        // userId가 있으면 userId로 조회 (로그인 상태)
        if (userId != null && !userId.isEmpty()) {
            try {
                return fileRepository.findByPathAndUserId(path, Long.parseLong(userId), pageable);
            } catch (Exception e) {
                System.out.println("파일 조회 실패: " + e.getMessage());
            }
        } else {
            try {
                return fileRepository.findByPathAndDeviceId(path, deviceId, pageable);
            } catch (Exception e) {
                System.out.println("파일 조회 실패: " + e.getMessage());
            }

        }

        return Page.empty(pageable);
    }

    @Transactional(readOnly = true)
    public FileLocationResponse findLocation(
            Long fileId,
            String sort,
            int size,
            String deviceId,
            Authentication authentication
    ) {
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size는 1 이상 100 이하여야 합니다.");
        }

        SortSpec sortSpec = parseSort(sort);
        Long userId = extractUserId(authentication);
        FileEntity target = findOwnedFile(fileId, userId, deviceId);
        long absoluteIndex;

        if (userId != null) {
            absoluteIndex = countFilesBefore(target, sortSpec, userId, null);
        } else {
            absoluteIndex = countFilesBefore(target, sortSpec, null, deviceId);
        }

        long pageLong = absoluteIndex / size;
        if (pageLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("계산된 페이지 번호가 너무 큽니다.");
        }

        int page = (int) pageLong;
        int indexInPage = (int) (absoluteIndex % size);
        Pageable pageable = PageRequest.of(page, size, sortSpec.toSort());
        Page<FileDto> targetPage = userId != null
                ? fileRepository.findByPathAndUserId(target.getPath(), userId, pageable)
                : fileRepository.findByPathAndDeviceId(target.getPath(), deviceId, pageable);

        return FileLocationResponse.builder()
                .fileId(target.getId())
                .path(target.getPath())
                .page(page)
                .indexInPage(indexInPage)
                .absoluteIndex(absoluteIndex)
                .size(size)
                .sort(sortSpec.normalized())
                .content(targetPage.getContent())
                .hasPrevious(targetPage.hasPrevious())
                .hasNext(targetPage.hasNext())
                .build();
    }

    @Transactional
    public void recordRead(Long fileId, String deviceId, Authentication authentication) {
        FileEntity file = findOwnedFile(fileId, extractUserId(authentication), deviceId);
        file.setLastReadAt(LocalDateTime.now());
    }

    private FileEntity findOwnedFile(Long fileId, Long userId, String deviceId) {
        if (userId != null) {
            return fileRepository.findByIdAndUserId(fileId, userId)
                    .orElseThrow(() -> new FileNotFoundException(fileId));
        }
        if (deviceId != null && !deviceId.isBlank()) {
            return fileRepository.findByIdAndDeviceId(fileId, deviceId)
                    .orElseThrow(() -> new FileNotFoundException(fileId));
        }
        throw new UnauthorizedException("인증 정보 없음");
    }

    private long countFilesBefore(FileEntity target, SortSpec sortSpec, Long userId, String deviceId) {
        return switch (sortSpec.normalized()) {
            case "date,desc" -> userId != null
                    ? fileRepository.countBeforeDateDescByUserId(target.getPath(), userId, target.getDate(), target.getId())
                    : fileRepository.countBeforeDateDescByDeviceId(target.getPath(), deviceId, target.getDate(), target.getId());
            case "date,asc" -> userId != null
                    ? fileRepository.countBeforeDateAscByUserId(target.getPath(), userId, target.getDate(), target.getId())
                    : fileRepository.countBeforeDateAscByDeviceId(target.getPath(), deviceId, target.getDate(), target.getId());
            case "rating,desc" -> userId != null
                    ? fileRepository.countBeforeRatingDescByUserId(target.getPath(), userId, target.getRating(), target.getId())
                    : fileRepository.countBeforeRatingDescByDeviceId(target.getPath(), deviceId, target.getRating(), target.getId());
            case "rating,asc" -> userId != null
                    ? fileRepository.countBeforeRatingAscByUserId(target.getPath(), userId, target.getRating(), target.getId())
                    : fileRepository.countBeforeRatingAscByDeviceId(target.getPath(), deviceId, target.getRating(), target.getId());
            default -> throw new IllegalArgumentException("지원하지 않는 정렬 조건입니다.");
        };
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    private SortSpec parseSort(String sort) {
        if (sort == null) {
            throw new IllegalArgumentException("sort가 필요합니다.");
        }

        String[] parts = sort.trim().toLowerCase().split(",");
        if (parts.length != 2
                || (!parts[0].equals("date") && !parts[0].equals("rating"))
                || (!parts[1].equals("asc") && !parts[1].equals("desc"))) {
            throw new IllegalArgumentException(
                    "sort는 date,asc|desc 또는 rating,asc|desc 형식이어야 합니다."
            );
        }

        return new SortSpec(parts[0], Sort.Direction.fromString(parts[1]));
    }

    private record SortSpec(String property, Sort.Direction direction) {
        private Sort toSort() {
            return Sort.by(
                    new Sort.Order(direction, property),
                    new Sort.Order(direction, "id")
            );
        }

        private String normalized() {
            return property + "," + direction.name().toLowerCase();
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
            System.out.println("🔍 검색 - 로그인 상태, userId: " + user.getId() + ", keyword: " + keyword);
            return fileRepository.findByUserIdAndTitleContainingIgnoreCase(user.getId(), keyword, pageable);
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return fileRepository.findByDeviceIdAndTitleContainingIgnoreCase(deviceId, keyword, pageable);
        }
        System.out.println("검색 - 인증 정보 없음, 검색 실패");
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
            fileRepository.deleteByDeviceIdAndIdIn(deviceId, ids);
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

        if (body.containsKey("readingPreview")) {
            file.setReadingPreview((String) body.get("readingPreview"));
        }

        if (body.containsKey("anchorRatio")) {
            // anchorRatio는 0~1 사이의 값으로, 책에서 현재 위치가 어디쯤인지 나타냄 (예: 0.5면 책의 중간 지점)
            // 로그 남기기
            Logger.getLogger(FileService.class.getName()).info("📌 anchorRatio 업데이트: " + body.get("anchorRatio"));
            Double r = (Double) body.get("anchorRatio");
            file.setAnchorRatio(r);
        }

        // 완독 여부 업데이트
        file.setCompleted(completed);

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
    public boolean isDuplicate(String deviceId, String title, String path) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("X-Device-Id 헤더가 필요합니다.");
        }
        return fileRepository.existsByDeviceIdAndTitleAndPath(deviceId, title, path);
    }

    // 최근 읽은 파일 조회 (히스토리)
    public List<HistoryFileDto> getRecentFilesByUserId(Long userId) {
        return fileRepository.findRecentFileDtosByUserId(userId, org.springframework.data.domain.PageRequest.of(0, 100));
    }

    // 최근 읽은 파일 조회 (히스토리, 게스트용)
    public List<HistoryFileDto> getRecentFilesByDeviceId(String deviceId) {
        return fileRepository.findRecentFileDtosByDeviceId(deviceId, org.springframework.data.domain.PageRequest.of(0, 100));
    }

}
