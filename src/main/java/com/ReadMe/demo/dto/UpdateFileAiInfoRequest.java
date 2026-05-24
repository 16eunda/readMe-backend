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
    private String genre;
    private String keywords;
    private String mood;
    private String content;
    private String summary;
    private String target;

}

