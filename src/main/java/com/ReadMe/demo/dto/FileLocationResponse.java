package com.ReadMe.demo.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FileLocationResponse {
    private final Long fileId;
    private final String path;
    private final int page;
    private final int indexInPage;
    private final long absoluteIndex;
    private final int size;
    private final String sort;
    private final List<FileDto> content;
    private final boolean hasPrevious;
    private final boolean hasNext;
}
