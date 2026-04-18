package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.FileStatsDto;
import com.ReadMe.demo.security.CustomUserDetails;
import com.ReadMe.demo.service.FileStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files/stats")
public class FileStatsController {

    private final FileStatsService fileStatsService;

    @GetMapping
    public Map<String, Long> getStats(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
           Authentication authentication
    ) {
        return fileStatsService.getFileStats(authentication, deviceId);
    }


}
