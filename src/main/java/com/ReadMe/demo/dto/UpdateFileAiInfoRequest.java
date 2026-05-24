package com.ReadMe.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateFileAiInfoRequest {
    private String aiGenre;
    private String aiKeywords;
    private String aiMood;
    private String aiSummary;
    private String aiTarget;
    private String aiContent;
}

