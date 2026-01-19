package com.ReadMe.demo.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_read_log")
public class FileReadLog {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private FileEntity file;

    private LocalDateTime readAt;
}
