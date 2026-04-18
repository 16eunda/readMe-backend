package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.FileRankingDto;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.Authenticator;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ranking")
public class RankingController {

    private final RankingService rankingService;

    /// year, month 파라미터로 특정 기간 조회 가능 (없으면 현재 월/년 기준) - 유료인 경우
    /// 무료는 현재 월/년 기준으로만 조회 가능
    @GetMapping("/month")
    public List<FileRankingDto> getMonthlyRanking(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestHeader (value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        // year, month 파라미터로 특정 기간 조회 가능 (없으면 현재 월/년 기준)
        int y = (year != null) ? year : LocalDate.now().getYear();
        int m = (month != null) ? month : LocalDate.now().getMonthValue();

        if(authentication != null && authentication.isAuthenticated()) {
            Long userId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
            return rankingService.getMonthlyRankingByUserAndDate(userId, y, m);
        }

        return rankingService.getMonthlyRankingByDeviceAndDate(deviceId, y, m);
    }

    // 수정 후
    @GetMapping("/year")
    public ResponseEntity<?> getYearlyRanking(
            @RequestParam(required = false) Integer year,
            @RequestHeader (value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        // year 파라미터로 특정 년도 조회 가능 (없으면 현재 년도 기준)
        int y = (year != null) ? year : LocalDate.now().getYear();

        if(authentication != null && authentication.isAuthenticated()) {
            Long userId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
            return ResponseEntity.ok(rankingService.getYearlyRankingByUserAndDate(userId, y));
        }

        return ResponseEntity.ok(rankingService.getYearlyRankingByDeviceAndDate(deviceId, y));
    }
}
