package com.ReadMe.demo.service;

import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class FileStatsService {

    private final FileRepository fileRepository;

    public Map<String, Long> getFileStats(Authentication authentication, String deviceId) {
        long totalCount;
        long completedCount;
        long fiveStarCount;

        // userId가 있으면 userId로 조회 (로그인 사용자)
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            Long userId = ((CustomUserDetails) authentication.getPrincipal()).getUserId();
            totalCount = fileRepository.countByUserId(userId);
            completedCount = fileRepository.countCompletedFilesByUserId(userId);
            fiveStarCount = fileRepository.countByRatingAndUserId(5, userId);
        }
        // deviceId로 조회 (게스트)
        else if (deviceId != null && !deviceId.isEmpty()) {
            totalCount = fileRepository.countByDeviceIdAndUserIsNull(deviceId);
            completedCount = fileRepository.countCompletedFilesByDeviceIdAndUserIsNull(deviceId);
            fiveStarCount = fileRepository.countByRatingAndDeviceIdAndUserIsNull(5, deviceId);
        }
        // 둘 다 없으면 0
        else {
            totalCount = 0;
            completedCount = 0;
            fiveStarCount = 0;
        }

        return Map.of(
                "totalCount", totalCount,
                "completedCount", completedCount,
                "fiveStarCount", fiveStarCount
        );
    }
}
