package com.ReadMe.demo.controller;

import com.ReadMe.demo.service.FileStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files/stats")
public class FileStatsController {

    private final FileStatsService fileStatsService;

    @GetMapping
    public Map<String, Long> getStats() {
        return fileStatsService.getFileStats();
    }
}
