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

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;
    private final FileReadLogRepository readLogRepository;

    // 한 번에 돌려주는 목록 크기 상한. 앱은 15개씩 요청한다. 상한이 없으면 size 하나로 전체 서재를 메모리에 올린다.
    private static final int MAX_PAGE_SIZE = 100;
    // FileEntity.review 컬럼 길이. 앱 입력창은 300자까지 받는다.
    private static final int MAX_REVIEW_LENGTH = 1000;

    // 제목 정규화 (확장자 제거)
    // "MyBook.epub" -> "MyBook"
    private String normalizeTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("\\.txt|\\.epub|\\.pdf", "").trim();
    }

    // 파일 저장
    public FileEntity saveFile(FileEntity file, String deviceId, Authentication authentication) {
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

        // 요청 본문은 FileEntity 로 바로 받으므로 서버가 정하는 값은 여기서 비운다.
        // id 를 비우지 않으면 남의 파일 id 를 넣어 그 파일을 덮어쓰고, user 를 비우지 않으면 남의 서재에 파일을 넣을 수 있다.
        file.setId(null);
        file.setUser(null);
        file.setAiGenre(null);
        file.setAiKeywords(null);
        file.setAiMood(null);
        file.setAiContent(null);
        file.setAiSummary(null);
        file.setAiTarget(null);
        file.setAiAnalyzedAt(null);
        file.setAnalysisStartedAt(null);

        file.setCompleted(false);
        file.setDeviceId(deviceId);
        file.setAnalysisStatus("PENDING");

        // 로그인 상태면 userId도 저장
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            UserEntity user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
            file.setUser(user);
        }

        // 분석은 여기서 요청하지 않는다. 구독자의 분석 안 된 책은 AnalysisBacklogScheduler 가 1분 안에 대기열에 넣는다.
        // 중복 여부는 /files/check에서 안내만 한다.
        return fileRepository.saveAndFlush(file);
    }

    // 파일조회
    // 경로로 조회 (로그인/게스트 모두 지원, 페이징/정렬)
    // 조회 실패(DB 장애 등)를 빈 목록으로 바꾸지 않는다. 예전에는 200 + 빈 목록을 돌려줘서
    // 앱이 "서재가 비었다"로 보고 캐시까지 빈 목록으로 덮어썼다. 앱은 오류 응답이면 기존 목록을 유지한다.
    public Page<FileDto> getFilesByPath(String path, String deviceId, String userId, int page, int size, String sort) {
        SortSpec sortSpec = parseSort(sort);
        validatePageSize(size);
        Pageable pageable = PageRequest.of(page, size, sortSpec.toSort());

        // userId가 있으면 userId로 조회 (로그인 상태)
        if (userId != null && !userId.isEmpty()) {
            return fileRepository.findByPathAndUserId(path, Long.parseLong(userId), pageable);
        }
        return fileRepository.findByPathAndDeviceId(path, deviceId, pageable);
    }

    @Transactional(readOnly = true)
    public FileLocationResponse findLocation(
            Long fileId,
            String sort,
            int size,
            String deviceId,
            Authentication authentication
    ) {
        validatePageSize(size);

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
            return fileRepository.findByIdAndDeviceIdAndUserIsNull(fileId, deviceId)
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

    private static void validatePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
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
    // 정렬은 목록과 같은 규칙(date|rating + id)을 쓴다. 예전에는 아무 컬럼 이름이나 받아 없는 컬럼이면 500 이 났고,
    // 별점이 같은 책이 많으면 페이지 경계가 흔들려 무한 스크롤에서 책이 겹치거나 빠졌다.
    public Page<FileDto> searchFiles(String keyword, int page, int size, String sort, String deviceId, Authentication authentication) {
        SortSpec sortSpec = parseSort(sort);
        validatePageSize(size);
        Pageable pageable = PageRequest.of(page, size, sortSpec.toSort());

        UserEntity user = null;

        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            user = ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }


        if (user != null) {
            return fileRepository.findByUserIdAndTitleContainingIgnoreCase(user.getId(), keyword, pageable);
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return fileRepository.findByDeviceIdAndTitleContainingIgnoreCase(deviceId, keyword, pageable);
        }
        return Page.empty(pageable);
    }

    // 파일 정보 업데이트 (제목, 리뷰, 별점, 경로)
    @Transactional
    public FileEntity updateFile(Long id, Map<String, Object> body, String deviceId, Authentication authentication) {
        // 소유권 검증. 예전에는 findById 만 해서 남의 파일도 수정할 수 있었다.
        FileEntity file = findOwnedFile(id, extractUserId(authentication), deviceId);

        // 값의 형식이 틀리면 400 이다. 예전에는 형변환 오류(500)나 NOT NULL 위반(409)이 났다.
        if (body.containsKey("title")) {
            file.setTitle(requiredText(body, "title"));
        }
        if (body.containsKey("review")) {
            String review = optionalText(body, "review");
            if (review != null && review.length() > MAX_REVIEW_LENGTH) {
                throw new IllegalArgumentException("리뷰는 " + MAX_REVIEW_LENGTH + "자 이하여야 합니다.");
            }
            file.setReview(review);
        }
        if (body.get("rating") != null) {
            if (!(body.get("rating") instanceof Number rating)) {
                throw new IllegalArgumentException("rating은 숫자여야 합니다.");
            }
            file.setRating(rating.intValue());
        }
        if (body.containsKey("path")) {
            file.setPath(requiredText(body, "path"));
        }

        return fileRepository.save(file);
    }

    // 파일삭제
    // 같은 삭제가 다시 오거나(재시도) 동시에 와도(연타·여러 기기) 200 이다. 이미 없는 파일은 건너뛴다.
    @Transactional
    public void deleteFiles(List<Long> ids, String deviceId, Authentication authentication) {
        if (ids == null) {
            throw new IllegalArgumentException("삭제할 파일 id(ids)가 필요합니다.");
        }

        Long userId = extractUserId(authentication);

        // 로그인 상태면 userId로 삭제, 게스트 상태면 deviceId로 삭제
        List<Long> ownedIds;
        if (userId != null) {
            ownedIds = ids.isEmpty() ? List.of() : fileRepository.findIdsByUserIdAndIdIn(userId, ids);
        } else if (deviceId != null && !deviceId.isBlank()) {
            ownedIds = ids.isEmpty() ? List.of() : fileRepository.findGuestIdsByDeviceIdAndIdIn(deviceId, ids);
        } else {
            // deviceId 없이 삭제하면 device_id IS NULL 조건이 되어 소유자를 확인할 수 없다.
            throw new UnauthorizedException("인증 정보 없음");
        }

        fileRepository.deleteFilesWithReadLogs(ownedIds);
    }

    // 파일 ID로 조회 (본인 파일만)
    @Transactional(readOnly = true)
    public FileEntity getFileById(Long id, String deviceId, Authentication authentication) {
        return findOwnedFile(id, extractUserId(authentication), deviceId);
    }

    // 파일 프로그래스 저장
    @Transactional
    public FileEntity updateProgress(Long id, Map<String, Object> body, String deviceId, Authentication authentication) {
        // 소유권 검증. 예전에는 findById 만 해서 남의 진행률도 바꿀 수 있었다.
        FileEntity file = findOwnedFile(id, extractUserId(authentication), deviceId);

        // 완독여부
        boolean completed = false;

        if (body.get("progress") instanceof Number p) {
            completed = p.doubleValue() >= 0.99; // 99% 이상이면 완독으로 간주
            file.setProgress(p.doubleValue());
        }

        if (body.containsKey("epubCfi")) {
            file.setEpubCfi(optionalText(body, "epubCfi"));
        }

        if (body.containsKey("readingPreview")) {
            file.setReadingPreview(optionalText(body, "readingPreview"));
        }

        if (body.get("anchorRatio") instanceof Number r) {
            // anchorRatio는 0~1 사이의 값으로, 책에서 현재 위치가 어디쯤인지 나타냄 (예: 0.5면 책의 중간 지점)
            // JSON 이 0 이나 1 로 오면 Integer 로 역직렬화되므로 Double 캐스팅은 ClassCastException 이 난다.
            file.setAnchorRatio(r.doubleValue());
        }

        // 완독 여부 업데이트
        file.setCompleted(completed);

        // 분석은 여기서 요청하지 않는다. 책을 열면 lastReadAt 이 바뀌어(recordRead) 주기 점검에서 먼저 분석된다.

        // 👇 읽기 로그 기록 (같은 날은 1회만)
        if (body.containsKey("recordReadLog") && Boolean.TRUE.equals(body.get("recordReadLog"))) {
            LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
            LocalDateTime startOfNextDay = startOfDay.plusDays(1);

            if (readLogRepository.countByFileIdAndToday(id, startOfDay, startOfNextDay) == 0) {
                FileReadLog log = new FileReadLog();
                log.setFile(file);
                log.setReadAt(LocalDateTime.now());
                readLogRepository.save(log);
            }
            // 이미 오늘 로그가 있으면 아무것도 안 함
        }

        return fileRepository.save(file);
    }

    private static String optionalText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(key + "는 문자열이어야 합니다.");
        }
        return (String) value;
    }

    private static String requiredText(Map<String, Object> body, String key) {
        String value = optionalText(body, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + "가 필요합니다.");
        }
        return value;
    }

    // 중복 여부 판단 (안내용). 요청자 화면에 보이는 목록과 같은 범위에서 찾는다.
    public boolean isDuplicate(String deviceId, String title, String path, Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId != null) {
            return fileRepository.existsByUser_IdAndTitleAndPath(userId, title, path);
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("X-Device-Id 헤더가 필요합니다.");
        }
        return fileRepository.existsByDeviceIdAndUserIsNullAndTitleAndPath(deviceId, title, path);
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
