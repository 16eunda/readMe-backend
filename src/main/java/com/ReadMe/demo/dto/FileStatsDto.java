package com.ReadMe.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FileStatsDto {

    private long totalCount;
    private long completedCount;
    private long fiveStarCount;
}