package com.ReadMe.demo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FolderDeleteRequest {
    private boolean force; // true면 연쇄삭제(강제), false면 비었을 때만 삭제
}
