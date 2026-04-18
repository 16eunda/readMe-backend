package com.ReadMe.demo.service;

import com.ReadMe.demo.dto.FileRankingDto;
import com.ReadMe.demo.repository.FileReadLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RankingService {

    private final FileReadLogRepository repository;

    // 최근 1개월 랭킹 조회 (로그인 사용자)
    public List<FileRankingDto> getMonthlyRankingByUser(Long userId) {
        LocalDateTime from = LocalDateTime.now().minusMonths(1);
        return getRankingSince(from, userId, null);
    }

    // 최근 1개월 랭킹 조회 (게스트)
    public List<FileRankingDto> getMonthlyRankingByDevice(String deviceId) {
        LocalDateTime from = LocalDateTime.now().minusMonths(1);
        return getRankingSince(from, null, deviceId);
    }

    // 최근 1년 랭킹 조회 (로그인 사용자)
    public List<FileRankingDto> getYearlyRankingByUser(Long userId) {
        LocalDateTime from = LocalDateTime.now().minusYears(1);
        return getRankingSince(from, userId, null);
    }

    // 최근 1년 랭킹 조회 (게스트)
    public List<FileRankingDto> getYearlyRankingByDevice(String deviceId) {
        LocalDateTime from = LocalDateTime.now().minusYears(1);
        return getRankingSince(from, null, deviceId);
    }

    // 공통 랭킹 조회 메서드 - 기간, userId/deviceId로 조회
    private List<FileRankingDto> getRankingSince(LocalDateTime from, Long userId, String deviceId) {
        PageRequest pageRequest = PageRequest.of(0, 50);

        // userId가 있으면 userId로 조회 (로그인 사용자)
        if (userId != null && userId > 0) {
            return repository.findRankingSinceByUserId(from, userId, pageRequest);
        }

        // deviceId로 조회 (게스트)
        if (deviceId != null && !deviceId.isEmpty()) {
            return repository.findRankingSinceByDeviceId(from, deviceId, pageRequest);
        }

        // 둘 다 없으면 빈 리스트
        return Collections.emptyList();
    }

    /*** 유료 사용자 랭킹 조회 (로그인 사용자) ***/
    // 특정 월/년 랭킹 조회 (로그인 사용자)
    public List<FileRankingDto> getMonthlyRankingByUserAndDate(Long userId, int year, int month) {
        LocalDateTime from = LocalDateTime.of(year, month, 1, 0, 0); // 해당 월의 1일 00:00:00
        LocalDateTime to = from.plusMonths(1);  // 해당 월의 마지막 날 23:59:59
        return repository.findRankingByUserIdAndDateRange(userId, from, to, PageRequest.of(0, 50));
    }

    // 특정 월/년 랭킹 조회 (게스트)
    public List<FileRankingDto> getMonthlyRankingByDeviceAndDate(String deviceId, int year, int month) {
        LocalDateTime from = LocalDateTime.of(year, month, 1, 0, 0); // 해당 월의 1일 00:00:00
        LocalDateTime to = from.plusMonths(1); // 해당 월의 마지막 날 23:59:59
        return repository.findRankingByDeviceIdAndDateRange(deviceId, from, to, PageRequest.of(0, 50));
    }

    // 특정 년도 랭킹 조회 (로그인 사용자)
    public List<FileRankingDto> getYearlyRankingByUserAndDate(Long userId, int year) {
        LocalDateTime from = LocalDateTime.of(year, 1, 1, 0, 0); // 해당 년도의 1월 1일 00:00:00
        LocalDateTime to = from.plusYears(1); // 해당 년도의 마지막 날 23:59:59
        return repository.findRankingByUserIdAndDateRange(userId, from, to, PageRequest.of(0, 50));
    }

    // 특정 년도 랭킹 조회 (게스트)
    public List<FileRankingDto> getYearlyRankingByDeviceAndDate(String deviceId, int year) {
        LocalDateTime from = LocalDateTime.of(year, 1, 1, 0, 0); // 해당 년도의 1월 1일 00:00:00
        LocalDateTime to = from.plusYears(1); // 해당 년도의 마지막 날 23:59:59
        return repository.findRankingByDeviceIdAndDateRange(deviceId, from, to, PageRequest.of(0, 50));
    }
}
