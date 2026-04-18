package com.ReadMe.demo.exception;

import com.ReadMe.demo.dto.FolderBulkDeleteInfo;
import lombok.Getter;

@Getter
public class FolderNotEmptyException extends RuntimeException {

    private final FolderBulkDeleteInfo info;

    public FolderNotEmptyException(FolderBulkDeleteInfo info) {
        this.info = info;
    }
}