package com.ReadMe.demo.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FolderBulkDeleteRequest {

    private List<Long> folderIds;

    private boolean force;
}