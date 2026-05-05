package com.ReadMe.demo.controller;

import com.ReadMe.demo.dto.FolderBulkDeleteRequest;
import com.ReadMe.demo.dto.FolderDto;
import com.ReadMe.demo.dto.FolderRequest;
import com.ReadMe.demo.service.FolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    // 폴더 조회
    @GetMapping
    public List<FolderDto> getFolders(
            @RequestParam(value = "path", required = false) String path,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication)
    {
        return folderService.getFolders(path, deviceId, authentication);
    }

    // 폴더 저장
    @PostMapping
    public FolderDto save(
            @RequestBody FolderRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        return folderService.save(request, deviceId, authentication);
    }

    // 폴더 업데이트 (이름, 경로)
    @PatchMapping("/{id}")
    public FolderDto updateFolder(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        return folderService.updateFolder(id, body);
    }


    // 폴더 삭제
    @DeleteMapping("/{id}")
    public void deleteFolder(@RequestBody FolderRequest request,
                             @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                             Authentication authentication) {
        folderService.delete(request, deviceId, authentication);
    }

    @PostMapping("/bulk-delete")
    public void bulkDelete(
            @RequestBody FolderBulkDeleteRequest request,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            Authentication authentication
    ) {
        folderService.bulkDelete(request, deviceId, authentication);
    }

    // 폴더 이동 (경로 변경)
    @PutMapping("/{id}")
    public ResponseEntity<FolderDto> moveFolder(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newPath = body.get("path");
        FolderDto updated = folderService.moveFolder(id, newPath);
        return ResponseEntity.ok(updated);
    }
}
