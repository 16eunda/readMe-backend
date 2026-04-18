package com.ReadMe.demo.dto;

import lombok.*;

@Getter
@AllArgsConstructor
public class FolderBulkDeleteInfo {

    private int folderCount;

    private int fileCount;

    private boolean hasChildren;
}

