package com.ReadMe.demo.controller;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.repository.FolderRepository;
import com.ReadMe.demo.service.FolderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin
@RequestMapping("/folders")
public class FolderController {

    private final FolderRepository folderRepository;
    private final FolderService folderService;

    public FolderController(FolderRepository folderRepository, FolderService folderService) {
        this.folderRepository = folderRepository;
        this.folderService = folderService;
    }
    @GetMapping
    public List<FolderEntity> getAll() {
        return folderRepository.findAll();
    }

    @PostMapping
    public FolderEntity save(@RequestBody FolderEntity folder) {
        return folderRepository.save(folder);
    }

    @DeleteMapping("/{id}")
    public void deleteFolder(@PathVariable Long id) {
        folderService.delete(id);
    }
}
