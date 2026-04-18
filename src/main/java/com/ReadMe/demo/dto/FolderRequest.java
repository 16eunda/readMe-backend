package com.ReadMe.demo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FolderRequest {
    private String name;
    private String path;
    private String userId;  // 로그인한 사용자 ID (선택적)
    private String deviceId; // 게스트 모드용 (선택적
    private Long id; // 폴더 ID (업데이트 시 필요)
}
