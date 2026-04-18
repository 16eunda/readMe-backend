package com.ReadMe.demo.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "folder_entity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String path;   // 부모 폴더 id 혹은 root

    // 사용자 소유권 관리 (파일과 동일한 방식)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    private String deviceId;  // 게스트 모드용
}
