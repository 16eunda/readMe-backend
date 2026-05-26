package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.dto.FileDto;
import com.ReadMe.demo.dto.FileGenreKeywordDto;
import com.ReadMe.demo.dto.RecommendationResponse;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.RecRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecService {
    private final RecRepository recRepository;
    private final FileRepository fileRepository;

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
            Long userId = extractUserId(authentication);

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
        List<FileEntity> recommendations = new ArrayList<>();

        // 사용자의 선호 키워드 수집 (최근 읽은 파일들에서)
        Set<String> preferredKeywords = collectPreferredKeywords(userId, deviceId);
        log.info("🔑 선호 키워드: {}", preferredKeywords);

        // 1️⃣ 장르 + 키워드 기반 추천 (분석된 파일만)
        Optional<String> preferredGenre = findPreferredGenre(userId, deviceId);
        if (preferredGenre.isPresent()) {
            List<FileEntity> genreBased = (userId != null)
                    ? recRepository.findByAiGenreAndLastReadAtIsNullAndUserId(preferredGenre.get(), userId)
                    : recRepository.findByAiGenreAndLastReadAtIsNullAndDeviceIdAndUserIsNull(preferredGenre.get(), deviceId);

            // 키워드 유사도 높은 순으로 정렬
            List<FileEntity> sorted = sortByKeywordSimilarity(genreBased, preferredKeywords);
            recommendations.addAll(sorted.stream()
                    .limit(GENRE_BASED_LIMIT)
                    .toList());
            log.info("📖 장르+키워드 기반 추천 {}건 (장르: {})",
                    Math.min(sorted.size(), GENRE_BASED_LIMIT), preferredGenre.get());
        }

        // 2️⃣ 별점 기반 추천 (로그인 유저만, 분석된 파일만)
        if (userId != null && recommendations.size() < MAX_RECOMMENDATIONS) {
            List<FileEntity> highRatedFiles = fileRepository
                    .findHighRatedFilesByUserId(userId, MIN_RATING_FOR_RECOMMENDATION);

            if (!highRatedFiles.isEmpty()) {
                Set<String> favoriteGenres = highRatedFiles.stream()
                        .map(FileEntity::getAiGenre)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (preferredGenre.isPresent()) {
                    favoriteGenres.remove(preferredGenre.get());
                }

                for (String genre : favoriteGenres) {
                    if (recommendations.size() >= MAX_RECOMMENDATIONS) break;

                    List<FileEntity> similarFiles = recRepository
                            .findByAiGenreAndLastReadAtIsNullAndUserId(genre, userId);

                    // 키워드 유사도 높은 순으로 정렬 후 추천
                    sortByKeywordSimilarity(similarFiles, preferredKeywords).stream()
                            .filter(f -> !recommendations.contains(f))
                            .findFirst()
                            .ifPresent(recommendations::add);
                }
            }
        }

        // 3️⃣ 읽다 만 파일 추천 (분석 여부 무관)
        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            List<FileEntity> inProgress = (userId != null)
                    ? recRepository.findByProgressBetweenAndUserId(0.1, 0.9, userId)
                    : recRepository.findByProgressBetweenAndDeviceIdAndUserIsNull(0.1, 0.9, deviceId);

            inProgress.stream()
                    .filter(f -> !recommendations.contains(f))
                    .limit(MAX_RECOMMENDATIONS - recommendations.size())
                    .forEach(recommendations::add);
        }

        // 여기까지가 "분석 기반 추천" 개수
        int smartRecCount = recommendations.size();

        // 4️⃣ 아직 부족하면: 안 읽은 파일 랜덤 추천
        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            List<FileEntity> randomUnread = (userId != null)
                    ? fileRepository.findUnreadRandomByUserId(userId)
                    : fileRepository.findUnreadRandomByDeviceId(deviceId);

            randomUnread.stream()
                    .filter(f -> !recommendations.contains(f))
                    .limit(MAX_RECOMMENDATIONS - recommendations.size())
                    .forEach(recommendations::add);
        }

        // 5️⃣ 그래도 부족하면: 오래 전에 읽은 파일
        if (recommendations.size() < MAX_RECOMMENDATIONS) {
            List<FileEntity> oldFiles = (userId != null)
                    ? fileRepository.findOldestReadFilesByUserId(userId)
                    : fileRepository.findOldestReadFilesByDeviceId(deviceId);

            oldFiles.stream()
                    .filter(f -> !recommendations.contains(f))
                    .limit(MAX_RECOMMENDATIONS - recommendations.size())
                    .forEach(recommendations::add);
        }

        // 최종 결과
        List<FileEntity> finalList = recommendations.stream()
                .distinct()
                .limit(MAX_RECOMMENDATIONS)
                .toList();

        // ===== 6️⃣ 최후 폴백: 위 모든 로직에서도 추천이 없으면 전체에서 랜덤 1권 =====
        if (finalList.isEmpty()) {
            List<FileEntity> anyRandom = (userId != null)
                    ? fileRepository.findAnyRandomByUserId(userId)
                    : fileRepository.findAnyRandomByDeviceId(deviceId);

            finalList = anyRandom.stream().limit(1).toList();
            log.info("🎲 최후 폴백 추천: 전체 랜덤 {}건", finalList.size());
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

        log.info("🎯 추천 결과: {}건 (분석기반 {}건, 랜덤/폴백 {}건) quality={}",
                finalList.size(), smartRecCount, finalList.size() - smartRecCount, quality);

        return RecommendationResponse.builder()
                .recommendations(finalList.stream().map(FileDto::from).toList())
                .quality(quality)
                .message(message)
                .analyzedCount(analyzedCount)
                .totalCount(totalCount)
                .build();
    }

    /**
     * 선호 키워드 수집 (최근 읽은 파일 + 별점 높은 파일에서)
     */
    private Set<String> collectPreferredKeywords(Long userId, String deviceId) {
        List<FileGenreKeywordDto> recentFiles;

        if (userId != null) {
            recentFiles = fileRepository
                    .findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(userId, PageRequest.of(0, 10));
        } else {
            recentFiles = fileRepository
                    .findTop10ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(deviceId, PageRequest.of(0, 10));
        }

        Set<String> keywords = new HashSet<>();
        for (FileGenreKeywordDto file : recentFiles) {
            if (file.getAiKeywords() != null && !file.getAiKeywords().isEmpty()) {
                Arrays.stream(file.getAiKeywords().split(","))
                        .map(String::trim)
                        .filter(k -> !k.isEmpty())
                        .forEach(keywords::add);
            }
        }

        return keywords;
    }

    /**
     * 키워드 유사도 높은 순으로 정렬
     * - 후보 파일의 키워드와 선호 키워드가 얼마나 겹치는지 점수 계산
     */
    private List<FileEntity> sortByKeywordSimilarity(List<FileEntity> candidates, Set<String> preferredKeywords) {
        if (preferredKeywords.isEmpty()) {
            return candidates; // 키워드 없으면 원래 순서 유지
        }

        return candidates.stream()
                .sorted((a, b) -> {
                    int scoreA = calculateKeywordScore(a, preferredKeywords);
                    int scoreB = calculateKeywordScore(b, preferredKeywords);
                    return Integer.compare(scoreB, scoreA); // 높은 점수 우선
                })
                .toList();
    }

    /**
     * 파일의 키워드와 선호 키워드의 매칭 점수 계산
     */
    private int calculateKeywordScore(FileEntity file, Set<String> preferredKeywords) {
        if (file.getAiKeywords() == null || file.getAiKeywords().isEmpty()) {
            return 0;
        }

        Set<String> fileKeywords = Arrays.stream(file.getAiKeywords().split(","))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toSet());

        // 겹치는 키워드 수 = 점수
        int score = 0;
        for (String keyword : fileKeywords) {
            if (preferredKeywords.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    /**
     * 선호 장르 찾기 (최근 읽은 파일에서 가장 많은 장르)
     */
    private Optional<String> findPreferredGenre(Long userId, String deviceId) {
        List<FileGenreKeywordDto> recentFiles;

        if (userId != null) {
            recentFiles = fileRepository
                    .findTop10ByUserIdAndLastReadAtIsNotNullOrderByLastReadAtDesc(userId, PageRequest.of(0, 10));
        } else {
            recentFiles = fileRepository
                    .findTop10ByDeviceIdAndUserIsNullAndLastReadAtIsNotNullOrderByLastReadAtDesc(deviceId, PageRequest.of(0, 10));
        }

        if (recentFiles.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Long> genreCount = recentFiles.stream()
                .filter(f -> f.getAiGenre() != null && !"미분류".equals(f.getAiGenre()))
                .collect(Collectors.groupingBy(
                        FileGenreKeywordDto::getAiGenre,
                        Collectors.counting()
                ));

        if (genreCount.isEmpty()) {
            return Optional.empty();
        }

        String mostCommonGenre = genreCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        log.info("📊 선호 장르: {}", mostCommonGenre);
        return Optional.ofNullable(mostCommonGenre);
    }

    private Long extractUserId(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            Long userId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
            return userId;
        }

        return null;
    }
}
