package com.ReadMe.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class HistoryFileDto {
    private Long id;
    private String title;
    private LocalDateTime lastReadAt;
    private Integer rating;
    private String uri;
}
