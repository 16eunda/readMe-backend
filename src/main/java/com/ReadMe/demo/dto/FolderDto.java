package com.ReadMe.demo.dto;

import com.ReadMe.demo.domain.FolderEntity;
import lombok.Getter;

@Getter
public class FolderDto {
    private final Long id;
    private final String name;
    private final String path;
    private final String deviceId;
    private final Long userId;

    public FolderDto(FolderEntity folder) {
        this.id = folder.getId();
        this.name = folder.getName();
        this.path = folder.getPath();
        this.deviceId = folder.getDeviceId();
        this.userId = folder.getUser() != null ? folder.getUser().getId() : null;
    }

    public static FolderDto from(FolderEntity folder) {
        return new FolderDto(folder);
    }
}
