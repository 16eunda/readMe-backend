package com.ReadMe.demo.service;

import com.ReadMe.demo.dto.FileRankingDto;
import com.ReadMe.demo.repository.FileReadLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RankingService {

    private final FileReadLogRepository repository;

    public List<FileRankingDto> getMonthlyRanking() {
        LocalDateTime from = LocalDateTime.now().minusMonths(1);
        return repository.findRankingSince(from, PageRequest.of(0, 50));
    }

    public List<FileRankingDto> getYearlyRanking() {
        LocalDateTime from = LocalDateTime.now().minusYears(1);
        return repository.findRankingSince(from, PageRequest.of(0, 50));
    }
}

