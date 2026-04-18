package com.ReadMe.demo.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FileDeleteRequest {
    private List<Long> ids; // 삭제할 파일 ID 목록
}
