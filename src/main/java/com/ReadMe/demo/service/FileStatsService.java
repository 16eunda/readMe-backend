package com.ReadMe.demo.service;

import com.ReadMe.demo.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class FileStatsService {

    private final FileRepository fileRepository;

    public Map<String, Long> getFileStats() {
        long totalCount = fileRepository.count();
        long completedCount = fileRepository.countByProgress(1.0);
        long fiveStarCount = fileRepository.countByRating(5);

        return Map.of(
                "totalCount", totalCount,
                "completedCount", completedCount,
                "fiveStarCount", fiveStarCount
        );
    }
}
