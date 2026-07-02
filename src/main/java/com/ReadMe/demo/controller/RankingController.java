package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.FileRankingDto;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.exception.PremiumRequiredException;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.service.RankingService;
import com.ReadMe.demo.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.logging.Log;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ranking")
public class RankingController {

    private final RankingService rankingService;
    private final SubscriptionService subscriptionService;

    /// year, month 파라미터로 특정 기간 조회 가능 (없으면 현재 월/년 기준) - 유료인 경우
    /// 무료는 현재 월/년 기준으로만 조회 가능
    @GetMapping("/month")
    public List<FileRankingDto> getMonthlyRanking(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestHeader (value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        // year, month 파라미터가 있으면 그대로 사용, 없으면 현재 년도/월 기준으로 랭킹 조회
        LocalDate today = LocalDate.now();
        int y = (year != null) ? year : today.getYear();
        int m = (month != null) ? month : today.getMonthValue();

        UserEntity user = extractUser(authentication);

        // 유료기능이지만 프리미엄이 아닌 경우 예외 발생
        if (isCustomMonth(year, month, today) && !subscriptionService.isPremium(user, deviceId)) {
            throw new PremiumRequiredException();
        }

        // 로그인한 사용자면 userId 기준으로 랭킹 조회
        if(user != null) {
            Long userId = user.getId();
            // year, month 파라미터가 있으면 해당 년도/월, 없으면 현재 년도/월 기준으로 랭킹 조회
            // 위에서 유료, 프리미엄 체크 했으니 그냥 조회
            return rankingService.getMonthlyRankingByUserAndDate(userId, y, m);
        }

        // 아니면 deviceId 기준으로 랭킹 조회
        return rankingService.getMonthlyRankingByDeviceAndDate(deviceId, y, m);
    }

    /// year 파라미터로 특정 년도 조회 가능 (없으면 현재 년도 기준) - 유료인 경우,
    ///  무료는 현재 년도 기준으로만 조회 가능
    @GetMapping("/year")
    public ResponseEntity<?> getYearlyRanking(
            @RequestParam(required = false) Integer year,
            @RequestHeader (value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        LocalDate today = LocalDate.now();

        // year 파라미터가 있으면 해당 년도, 없으면 현재 년도 기준으로 랭킹 조회
        int y = (year != null) ? year : today.getYear();

        UserEntity user = extractUser(authentication);

        // 유료기능이지만 프리미엄이 아닌 경우 예외 발생
        if (isCustomYear(year, today) && !subscriptionService.isPremium(user, deviceId)) {
            throw new PremiumRequiredException();
        }

        // 로그인한 사용자면 userId 기준으로
        if(user != null) {
            Long userId = user.getId();
            // year 파라미터가 있으면 해당 년도, 없으면 현재 년도 기준으로 랭킹 조회
            // 위에서 유료, 프리미엄 체크 했으니 그냥 조회
            return ResponseEntity.ok(rankingService.getYearlyRankingByUserAndDate(userId, y));
        }

        // 아니면 deviceId 기준으로 랭킹 조회
        return ResponseEntity.ok(rankingService.getYearlyRankingByDeviceAndDate(deviceId, y));
    }

    // year, month 파라미터가 있고, 그 값이 현재 년도/월과 다르면 true 반환 (즉 사용자가 커스텀 기간을 요청한 경우, 유료기능)
    private boolean isCustomMonth(Integer year, Integer month, LocalDate today) {
        if (year == null && month == null) {
            return false;
        }
        int requestedYear = year != null ? year : today.getYear();
        int requestedMonth = month != null ? month : today.getMonthValue();
        return requestedYear != today.getYear() || requestedMonth != today.getMonthValue();
    }

    // year 파라미터가 있고, 그 값이 현재 년도와 다르면 true 반환 (즉 사용자가 커스텀 기간을 요청한 경우, 유료기능)
    private boolean isCustomYear(Integer year, LocalDate today) {
        return year != null && year != today.getYear();
    }

    private UserEntity extractUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails details) {
            return details.getUser();
        }
        return null;
    }
}
