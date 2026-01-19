package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;

    // 파일 저장
    public FileEntity saveFile(FileEntity file) {
        System.out.println("=== Received From RN ===");
        System.out.println(file);
        return fileRepository.save(file);
    }

    // 파일조회
    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    // 파일 프로그래스 저장
    public  FileEntity updateProgress(Long id, Map<String, Object> body) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));

        if (body.containsKey("progress")) {
            Number p = (Number) body.get("progress");
            file.setProgress(p.doubleValue());
        }

        if (body.containsKey("epubCfi")) {
            file.setEpubCfi((String) body.get("epubCfi"));
        }

        // 읽은 시점 기록 (핵심)
        file.setLastReadAt(LocalDateTime.now());

        return fileRepository.save(file);
    }

    // 중복 여부 판단
    public boolean isDuplicate(String title, String path) {
        return fileRepository.existsByTitleAndPath(title, path);
    }

    // 최근 읽은 파일 조회 (히스토리)
    public List<FileEntity> getRecentFiles() {
        return fileRepository
                .findTop50ByLastReadAtIsNotNullOrderByLastReadAtDesc();
    }
}
