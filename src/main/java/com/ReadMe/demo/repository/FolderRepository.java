package com.ReadMe.demo.repository;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FolderRepository extends JpaRepository<FolderEntity, Long> {

    // userId로 폴더 조회 (로그인 사용자)
    List<FolderEntity> findByUserAndPath(UserEntity user, String path);

    // deviceId로 폴더 조회 (게스트, user가 null인 것만)
    List<FolderEntity> findByDeviceIdAndUserIsNullAndPath(String deviceId, String path);

    // userId와 id 리스트로 폴더 삭제 (보안 필터링)
    void deleteByUserAndIdIn(UserEntity user, List<Long> ids);

    // deviceId와 id 리스트로 폴더 삭제 (보안 필터링, 게스트)
    void deleteByDeviceIdAndUserIsNullAndIdIn(String deviceId, List<Long> ids);

    // deviceId를 userId로 연결 (로그인 시)
    @Modifying
    @Query("UPDATE FolderEntity f SET f.user.id = :userId WHERE f.deviceId = :deviceId AND f.user IS NULL")
    int linkDeviceToUser(@Param("deviceId") String deviceId, @Param("userId") Long userId);

    // 연결 가능한 폴더 수 확인
    long countByDeviceIdAndUserIsNull(String deviceId);

    // deviceId로 전체 폴더 조회 (게스트, user가 null인 것만)
    List<FolderEntity> findByDeviceIdAndUserIsNull(String deviceId);

    List<FolderEntity> findByUser(UserEntity user);
}


