package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.repository.FolderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FolderService {

    private final FolderRepository folderRepository;

    public FolderService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    public List<FolderEntity> getAll() {
        return folderRepository.findAll();
    }

    public FolderEntity save(FolderEntity folder) {
        return folderRepository.save(folder);
    }

    public void delete(Long id) {
        if (!folderRepository.existsById(id)) {
            throw new IllegalArgumentException("Folder not found: " + id);
        }

        folderRepository.deleteById(id);
    }

    // 필요하면 여기에 폴더 삭제/수정/하위 조회 등 로직 추가 가능
}
