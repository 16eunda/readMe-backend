package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(
        name = "files",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"title", "path"})
        }
)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String path;
    private String uri;
    private String preview;
    private String date;
    private int rating;
    private Double progress = 0.0;   // ★ 0~1 진행도 저장
    private String epubCfi;

    @Column(name = "last_read_at")
    private LocalDateTime lastReadAt;
}