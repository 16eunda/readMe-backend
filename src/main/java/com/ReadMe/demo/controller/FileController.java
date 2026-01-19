package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    @PostMapping
    public FileEntity saveFile(@RequestBody FileEntity file) {
        return fileService.saveFile(file);
    }

    @GetMapping
    public List<FileEntity> getFiles() {
        return fileService.getFiles();
    }

    // 히스토리 조회 (프론트에서 사용)
    @GetMapping("/history")
    public List<FileEntity> getRecentFiles() {
        return fileService.getRecentFiles();
    }

    @PatchMapping("/{id}/progress")
    public FileEntity updateProgress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
       return fileService.updateProgress(id, body);
    }

    // 파일 중복 체크
    @GetMapping("/check")
    public ResponseEntity<Map<String, Boolean>> checkDuplicate(
            @RequestParam String title,
            @RequestParam String path
    ) {
        boolean exists = fileService.isDuplicate(title, path);
        return ResponseEntity.ok(Map.of("exists", exists));
    }
}
