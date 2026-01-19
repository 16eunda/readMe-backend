package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolderRepository extends JpaRepository<FolderEntity, Long> { }
