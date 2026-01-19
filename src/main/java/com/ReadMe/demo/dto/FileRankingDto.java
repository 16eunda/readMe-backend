package com.ReadMe.demo.dto;

import java.time.LocalDateTime;

public record FileRankingDto(
        Long fileId,
        String title,
        long readCount,
        Double progress,
        int rating,
        LocalDateTime lastReadAt
) {}