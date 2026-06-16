package com.ReadMe.demo.service;

import com.ReadMe.demo.dto.FileGenreKeywordDto;
import com.ReadMe.demo.dto.RecFileDto;
import com.ReadMe.demo.dto.RecommendationResponse;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.RecRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.domain.UserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecService {
    private final RecRepository recRepository;
    private final FileRepository fileRepository;
    private final SubscriptionService subscriptionService;

    private static final int GENRE_BASED_LIMIT = 3;
    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int MIN_RATING_FOR_RECOMMENDATION = 4;

    /**
     * 추천 조회 (메인 진입점)
     * - 분석된 파일만으로 추천
     * - 분석이 부족하면 랜덤 + quality flag로 프론트에 알림
     */
    public RecommendationResponse getRecommendations(Authentication authentication, String deviceId) {
        try {
            UserEntity user = extractUser(authentication);
            Long userId = user != null ? user.getId() : null;

            if (!subscriptionService.isPremium(user, deviceId)) {
                return RecommendationResponse.premiumRequired();
            }

            if (userId != null) {
                return buildRecommendations(userId, null);
            } else if (deviceId != null && !deviceId.isEmpty()) {
                return buildRecommendations(null, deviceId);
            }

            return RecommendationResponse.builder()
                    .recommendations(Collections.emptyList())
                    .quality("NO_DATA")
                    .message("파일을 추가해보세요!")
                    .analyzedCount(0)
                    .totalCount(0)
                    .build();

        } catch (Exception e) {
            log.error("❌ 추천 조회 실패", e);
            return RecommendationResponse.builder()
                    .recommendations(Collections.emptyList())
                    .quality("NO_DATA")
                    .message("추천을 불러오는 데 실패했습니다.")
                    .analyzedCount(0)
                    .totalCount(0)
                    .build();
        }
    }

    /**
     * 추천 생성 (로그인/게스트 통합)
     */
    private RecommendationResponse buildRecommendations(Long userId, String deviceId) {
        // 분석 현황 파악
        long analyzedCount = (userId != null)
                ? fileRepository.countAnalyzedByUserId(userId)
                : fileRepository.countAnalyzedByDeviceId(deviceId);

        long totalCount = (userId != null)
                ? fileRepository.countAllByUserId(userId)
                : fileRepository.countAllByDeviceId(deviceId);

        log.info("📊 분석 현황: {}/{} 파일 분석 완료", analyzedCount, totalCount);

        // 파일이 아예 없는 경우
        if (totalCount == 0) {
            return RecommendationResponse.builder()
                    .recommendations(Collections.emptyList())
                    .quality("NO_DATA")
                    .message("파일을 추가해보세요!")
                    .analyzedCount(0)
                    .totalCount(0)
                    .build();
        }

        // 추천 파일 모으기
        List<RecFileDto> recommendations = new ArrayList<>();
        Map<String, Integer> stageAdded = new LinkedHashMap<>();
        stageAdded.put("genre_keyword", 0);
        stageAdded.put("rating", 0);
        stageAdded.put("in_progress", 0);
        stageAdded.put("random_unread", 0);
        stageAdded.put("old_read", 0);
        stageAdded.put("fallback_any_random", 0);

        // 사용자의 선호 키워드 수집 (최근 읽은 파일들에서)
        Set<String> preferredKeywords = collectPreferredKeywords(userId, deviceId);
        log.info("🔑 [추천] 선호 키워드: {}", preferredKeywords);

        // 1️⃣ 장르 + 키워드 기반 추천 (분석된 파일만)
        Optional<String> preferredGenre = findPreferredGenre(userId, deviceId);
        log.info("🧭 [추천] 1단계(장르+키워드) 시작 - preferredGenre={}", preferredGenre.orElse("없음"));
        if (preferredGenre.isPresent()) {
            List<RecFileDto> genreBased = (userId != null)
                    ? recRepository.findByAiGenreAndLastReadAtIsNullAndUserId(preferredGenre.get(), userId)
                    : recRepository.findByAiGenreAndLastReadAtIsNullAndDeviceId(preferredGenre.get(), deviceId);

            // 키워드 유사도 높은 순으로 정렬
            List<RecFileDto> sorted = sortByKeywordSimilarity(genreBased, preferredKeywords);
            int before = recommendations.size();
            recommendations.addAll(sorted.stream().limit(GENRE_BASED_LIMIT).toList());
            int added = recommendations.size() - before;
            stageAdded.put("genre_keyword", added);

            log.info("📖 [추천] 1단계 결과 - 후보={} 추가={} 누적={}", genreBased.size(), added, recommendations.size());
            log.info("📖 [추천] 1단계 추가목록={}",
                    recommendations.stream().skip(before).map(r -> r.getId() + ":" + r.getTitle()).toList());
        }

        // 2️⃣ 별점 기반 추천 (로그인 유저만, 분석된 파일만)
        if (userId != null && recommendations.size() < MAX_RECOMMENDATIONS) {
            log.info("⭐ [추천] 2단계(별점 기반) 시작 - 현재 누적={}", recommendations.size());

            Set<String> favoriteGenres = fileRepository
                    .findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(userId, PageRequest.of(0, 50))
                    .stream()
                    .filter(f -> f.getAiGenre() != null && f.getAiGenre().length() > 0)
                    .map(FileGenreKeywordDto::getAiGenre)
                    .collect(Collectors.toSet());

            if (preferredGenre.isPresent()) favoriteGenres.remove(preferredGenre.get());
            log.info("⭐ [추천] 2단계 장르 후보={}", favoriteGenres);

            int before = recommendations.size();
            for (String genre : favoriteGenres) {
                if (recommendations.size() >= MAX_RECOMMENDATIONS) break;
                List<RecFileDto> similarFiles = recRepository.findByAiGenreAndLastReadAtIsNullAndUserId(genre, userId);
                sortByKeywordSimilarity(similarFiles, preferredKeywords).stream()
                        .filter(f -> recommendations.stream().noneMatch(r -> r.getId().equals(f.getId())))
                        .findFirst()
                        .ifPresent(recommendations::add);
            }
            int added = recommendations.size() - before;
            stageAdded.put("rating", added);
            log.info("⭐ [추천] 2단계 결과 - 추가={} 누적={}", added, recommendations.size());
            log.info("⭐ [추천] 2단계 추가목록={}",
                    recommendations.stream().skip(before).map(r -> r.getId() + ":" + r.getTitle()).toList());
        }

        // 3️⃣ 읽다 만 파일 추천 (분석 여부 무관)
        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            log.info("⏳ [추천] 3단계(읽다 만 파일) 시작 - 현재 누적={}", recommendations.size());
            List<RecFileDto> inProgress = (userId != null)
                    ? recRepository.findByProgressBetweenAndUserId(0.1, 0.9, userId)
                    : recRepository.findByProgressBetweenAndDeviceId(0.1, 0.9, deviceId);

            int before = recommendations.size();
            inProgress.stream()
                    .filter(f -> recommendations.stream().noneMatch(r -> r.getId().equals(f.getId())))
                    .limit(MAX_RECOMMENDATIONS - recommendations.size())
                    .forEach(recommendations::add);
            int added = recommendations.size() - before;
            stageAdded.put("in_progress", added);
            log.info("⏳ [추천] 3단계 결과 - 후보={} 추가={} 누적={}", inProgress.size(), added, recommendations.size());
        }

        // 여기까지가 "분석 기반 추천" 개수
        int smartRecCount = recommendations.size();

        // 4️⃣ 아직 부족하면: 안 읽은 파일 랜덤 추천
        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            log.info("🎲 [추천] 4단계(안 읽은 랜덤) 시작 - 현재 누적={}", recommendations.size());
            List<RecFileDto> randomUnread = toRecFileDtoList(userId != null
                    ? fileRepository.findUnreadRandomByUserIdRaw(userId)
                    : fileRepository.findUnreadRandomByDeviceIdRaw(deviceId));

            int before = recommendations.size();
            randomUnread.stream()
                    .filter(f -> recommendations.stream().noneMatch(r -> r.getId().equals(f.getId())))
                    .limit(MAX_RECOMMENDATIONS - recommendations.size())
                    .forEach(recommendations::add);
            int added = recommendations.size() - before;
            stageAdded.put("random_unread", added);
            log.info("🎲 [추천] 4단계 결과 - 후보={} 추가={} 누적={}", randomUnread.size(), added, recommendations.size());
        }

        // 5️⃣ 그래도 부족하면: 오래 전에 읽은 파일
        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            log.info("🕰️ [추천] 5단계(오래 전에 읽은 파일) 시작 - 현재 누적={}", recommendations.size());
            List<RecFileDto> oldFiles = (userId != null)
                    ? fileRepository.findOldestReadFilesByUserId(userId)
                    : fileRepository.findOldestReadFilesByDeviceId(deviceId);

            int before = recommendations.size();
            oldFiles.stream()
                    .filter(f -> recommendations.stream().noneMatch(r -> r.getId().equals(f.getId())))
                    .limit(MAX_RECOMMENDATIONS - recommendations.size())
                    .forEach(recommendations::add);
            int added = recommendations.size() - before;
            stageAdded.put("old_read", added);
            log.info("🕰️ [추천] 5단계 결과 - 후보={} 추가={} 누적={}", oldFiles.size(), added, recommendations.size());
        }

        // 최종 결과
        List<RecFileDto> finalList = recommendations.stream().distinct().limit(MAX_RECOMMENDATIONS).toList();

        // ===== 6️⃣ 최후 폴백: 위 모든 로직에서도 추천이 없으면 전체에서 랜덤 1권 =====
        if (finalList.isEmpty()) {
            log.info("🆘 [추천] 6단계(최후 폴백) 시작");
            List<RecFileDto> anyRandom = toRecFileDtoList(userId != null
                    ? fileRepository.findAnyRandomByUserIdRaw(userId)
                    : fileRepository.findAnyRandomByDeviceIdRaw(deviceId));
            finalList = anyRandom.stream().limit(1).toList();
            stageAdded.put("fallback_any_random", finalList.size());
            log.info("🆘 [추천] 6단계 결과 - 추가={}", finalList.size());
        }

        // 품질 판단
        String quality;
        String message;

        if (finalList.isEmpty()) {
            // 파일 자체가 없는 경우
            quality = "NO_DATA";
            message = "파일을 추가해보세요!";
        } else if (analyzedCount == 0) {
            quality = "NO_DATA";
            message = "아직 AI 분석된 책이 없어요 📚\n책을 읽을수록 취향에 맞는 추천이 정확해져요!";
        } else if (smartRecCount == 0) {
            // 분석은 됐지만 추천 로직에서 아무것도 못 골라서 랜덤 폴백된 상태
            quality = "LOW_DATA";
            message = "아직 추천 데이터가 부족해요 😅\n책을 더 읽을수록 딱 맞는 추천을 드릴 수 있어요!";
        } else if (smartRecCount < 3) {
            quality = "LOW_DATA";
            message = "더 많은 책을 읽으면 추천이 정확해져요! (" + analyzedCount + "/" + totalCount + "권 분석됨)";
        } else {
            quality = "GOOD";
            message = null;
        }

        log.info("📌 [추천] 단계별 추가 집계={}", stageAdded);
        log.info("🎯 [추천] 최종 결과 {}건 (분석기반 {}건, 랜덤/폴백 {}건) quality={}",
                finalList.size(), smartRecCount, finalList.size() - smartRecCount, quality);
        log.info("🎯 [추천] 최종 목록={}",
                finalList.stream().map(r -> r.getId() + ":" + r.getTitle()).toList());

        return RecommendationResponse.builder()
                .recommendations(finalList)
                .quality(quality)
                .message(message)
                .analyzedCount(analyzedCount)
                .totalCount(totalCount)
                .build();
    }

    /** Object[] 네이티브 쿼리 결과 → RecFileDto 변환 */
    private List<RecFileDto> toRecFileDtoList(List<Object[]> rows) {
        return rows.stream().map(row -> new RecFileDto(
                row[0] != null ? ((Number) row[0]).longValue() : null,
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                row[6] != null ? ((Number) row[6]).doubleValue() : null,
                row[7] != null ? ((Number) row[7]).intValue() : null
        )).toList();
    }

    /**
     * 선호 키워드 수집 (최근 읽은 파일 + 별점 높은 파일에서)
     */
    private Set<String> collectPreferredKeywords(Long userId, String deviceId) {
        List<FileGenreKeywordDto> recentFiles = (userId != null)
                ? fileRepository.findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(userId, PageRequest.of(0, 10))
                : fileRepository.findTop10ByDeviceIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(deviceId, PageRequest.of(0, 10));

        Set<String> keywords = new HashSet<>();
        for (FileGenreKeywordDto file : recentFiles) {
            if (file.getAiKeywords() != null && !file.getAiKeywords().isEmpty()) {
                Arrays.stream(file.getAiKeywords().split(","))
                        .map(String::trim).filter(k -> !k.isEmpty())
                        .forEach(keywords::add);
            }
        }
        return keywords;
    }

    /**
     * 키워드 유사도 높은 순으로 정렬
     * - 후보 파일의 키워드와 선호 키워드가 얼마나 겹치는지 점수 계산
     */
    private List<RecFileDto> sortByKeywordSimilarity(List<RecFileDto> candidates, Set<String> preferredKeywords) {
        if (preferredKeywords.isEmpty()) return candidates;

        return candidates.stream()
                .sorted((a, b) -> Integer.compare(
                        calculateKeywordScore(b, preferredKeywords),
                        calculateKeywordScore(a, preferredKeywords)))
                .toList();
    }

    /**
     * 파일의 키워드와 선호 키워드의 매칭 점수 계산
     */
    private int calculateKeywordScore(RecFileDto file, Set<String> preferredKeywords) {
        if (file.getAiKeywords() == null || file.getAiKeywords().isEmpty()) return 0;
        return (int) Arrays.stream(file.getAiKeywords().split(","))
                .map(String::trim).filter(preferredKeywords::contains).count();
    }

    /**
     * 선호 장르 찾기 (최근 읽은 파일에서 가장 많은 장르)
     */
    private Optional<String> findPreferredGenre(Long userId, String deviceId) {
        List<FileGenreKeywordDto> recentFiles = (userId != null)
                ? fileRepository.findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(userId, PageRequest.of(0, 10))
                : fileRepository.findTop10ByDeviceIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(deviceId, PageRequest.of(0, 10));

        if (recentFiles.isEmpty()) return Optional.empty();

        Map<String, Long> genreCount = recentFiles.stream()
                .filter(f -> f.getAiGenre() != null && !"미분류".equals(f.getAiGenre()))
                .collect(Collectors.groupingBy(FileGenreKeywordDto::getAiGenre, Collectors.counting()));

        if (genreCount.isEmpty()) return Optional.empty();

        String genre = genreCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        log.info("📊 선호 장르: {}", genre);
        return Optional.ofNullable(genre);
    }

    private UserEntity extractUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails d) {
            return d.getUser();
        }
        return null;
    }
}
