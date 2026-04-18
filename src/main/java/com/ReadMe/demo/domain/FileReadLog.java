package com.ReadMe.demo.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_read_log")
public class FileReadLog {

    @Id
    @GeneratedValue
    private Long id;

    // 파일엔티티와 관계 맺기 (다대일)
    @ManyToOne(fetch = FetchType.LAZY)
    private FileEntity file;

    private LocalDateTime readAt;

    // getter/setter 추가
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public FileEntity getFile() { return file; }
    public void setFile(FileEntity file) { this.file = file; }

    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
