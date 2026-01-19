package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.FileRankingDto;
import com.ReadMe.demo.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ranking")
public class RankingController {

    private final RankingService rankingService;

    @GetMapping("/month")
    public List<FileRankingDto> month() {
        return rankingService.getMonthlyRanking();
    }

    @GetMapping("/year")
    public List<FileRankingDto> year() {
        return rankingService.getYearlyRanking();
    }
}

