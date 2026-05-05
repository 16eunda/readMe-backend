package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.FolderBulkDeleteInfo;
import com.ReadMe.demo.dto.FolderBulkDeleteRequest;
import com.ReadMe.demo.dto.FolderDto;
import com.ReadMe.demo.dto.FolderRequest;
import com.ReadMe.demo.exception.FolderNotEmptyException;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.FolderRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class FolderService {

    private final FolderRepository folderRepository;
    private final FileRepository fileRepository;

    // 공통 유틸 - Authentication에서 UserEntity 추출
    private UserEntity extractUser(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails) {
            return ((CustomUserDetails) authentication.getPrincipal()).getUser();
        }
        return null;
    }

    // userId 또는 deviceId로 폴더 조회 (보안 필터링)
    public List<FolderDto> getFolders(
            String path,
            String deviceId,
            Authentication authentication
    ) {
        UserEntity user = extractUser(authentication);

        if (user != null) {
            List<FolderEntity> folders = (path == null)
                    ? folderRepository.findByUser(user)
                    : folderRepository.findByUserAndPath(user, path);
            return folders.stream().map(FolderDto::from).toList();
        }

        List<FolderEntity> folders = (path == null)
                ? folderRepository.findByDeviceIdAndUserIsNull(deviceId)
                : folderRepository.findByDeviceIdAndUserIsNullAndPath(deviceId, path);
        return folders.stream().map(FolderDto::from).toList();
    }

    // 폴더 저장 (로그인 여부에 따라 userId 또는 deviceId로 저장)
    public FolderDto save(
            FolderRequest request,
            String deviceId,
            Authentication authentication
    ) {
        FolderEntity folder = new FolderEntity();
        folder.setName(request.getName());
        folder.setPath(request.getPath());

        UserEntity user = extractUser(authentication);
        if (user != null) {
            folder.setUser(user);
        } else {
            folder.setDeviceId(deviceId);
        }

        return FolderDto.from(folderRepository.save(folder));
    }

    // 폴더 업데이트 (이름, 경로)
    public FolderDto updateFolder(Long id, Map<String, Object> body) {
        FolderEntity folder = folderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("폴더를 찾을 수 없습니다"));

        if (body.containsKey("name")) {
            folder.setName((String) body.get("name"));
        }
        if (body.containsKey("path")) {
            folder.setPath((String) body.get("path"));
        }

        return FolderDto.from(folderRepository.save(folder));
    }

    // 폴더 ID로 하위 폴더 ID 수집 (BFS)
    private List<Long> collectFolderIds(FolderEntity root, UserEntity user, String deviceId) {

        List<Long> folderIds = new ArrayList<>();
        Queue<Long> queue = new LinkedList<>();

        queue.add(root.getId());

        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            folderIds.add(currentId);

            List<FolderEntity> children;

            if (user != null) {
                children = folderRepository.findByUserAndPath(user, currentId.toString());
            } else {
                children = folderRepository
                        .findByDeviceIdAndUserIsNullAndPath(deviceId, currentId.toString());
            }

            for (FolderEntity child : children) {
                queue.add(child.getId());
            }
        }

        return folderIds;
    }

    // 폴더 삭제
    @Transactional
    public void delete(FolderRequest request, String deviceId, Authentication authentication) {

        FolderEntity folder = folderRepository.findById(request.getId())
                .orElseThrow(() -> new RuntimeException("폴더를 찾을 수 없습니다"));

        UserEntity user = extractUser(authentication);

        // owner 검증
        assertFolderOwner(folder, user, deviceId);

        // 하위 폴더 id 수집
        List<Long> folderIds = collectFolderIds(folder, user, deviceId);

        // 파일 삭제 (폴더 안 + 하위 폴더 파일)
        if (user != null) {
            fileRepository.deleteByUserAndPathIn(user, folderIds);
        } else {
            fileRepository.deleteByDeviceIdAndUserIsNullAndPathIn(deviceId, folderIds);
        }

        // 폴더 삭제
        if (user != null) {
            folderRepository.deleteByUserAndIdIn(user, folderIds);
        } else {
            folderRepository.deleteByDeviceIdAndUserIsNullAndIdIn(deviceId, folderIds);
        }
    }

    // 폴더 소유자 검증 (삭제 권한 체크)
    private void assertFolderOwner(FolderEntity folder,
                                   UserEntity user,
                                   String deviceId) {

        if (user != null) {

            if (!user.equals(folder.getUser())) {
                throw new RuntimeException("삭제 권한 없음");
            }

        } else {

            if (folder.getUser() != null ||
                    !deviceId.equals(folder.getDeviceId())) {
                throw new RuntimeException("삭제 권한 없음");
            }

        }
    }

    @Transactional
    public void bulkDelete(FolderBulkDeleteRequest request,
                           String deviceId,
                           Authentication authentication) {

        List<Long> folderIds = request.getFolderIds();
        boolean force = request.isForce();

        UserEntity user = extractUser(authentication);

        Set<Long> allFolderIds = new HashSet<>();

        // 1️⃣ BFS로 모든 하위 폴더 수집
        for (Long folderId : folderIds) {

            FolderEntity folder = folderRepository.findById(folderId)
                    .orElseThrow(() -> new RuntimeException("폴더 없음"));

            assertFolderOwner(folder, user, deviceId);

            List<Long> ids = collectFolderIds(folder, user, deviceId);

            allFolderIds.addAll(ids);
        }

        // 삭제할 폴더 ID 리스트 (중복 제거)
        List<Long> deleteFolderIds = new ArrayList<>(allFolderIds);

        // 2️⃣ 파일 개수 계산
        long fileCount;

        if (user != null) {
            fileCount = fileRepository.countByUserAndPathIn(user, deleteFolderIds);
        } else {
            fileCount = fileRepository.countByDeviceIdAndUserIsNullAndPathIn(deviceId, deleteFolderIds);
        }

        // 하위 폴더 또는 파일 존재 여부
        boolean hasChildren = fileCount > 0 || deleteFolderIds.size() > folderIds.size();

        // 3️⃣ force=false면 경고
        if (!force && hasChildren) {

            FolderBulkDeleteInfo info = new FolderBulkDeleteInfo(
                    deleteFolderIds.size(),
                    (int) fileCount,
                    true
            );

            throw new FolderNotEmptyException(info);
        }

        // 4️⃣ 실제 삭제
        if (user != null) {

            fileRepository.deleteByUserAndPathIn(user, deleteFolderIds);

            folderRepository.deleteByUserAndIdIn(user, deleteFolderIds);

        } else {

            fileRepository.deleteByDeviceIdAndUserIsNullAndPathIn(deviceId, deleteFolderIds);

            folderRepository.deleteByDeviceIdAndUserIsNullAndIdIn(deviceId, deleteFolderIds);
        }
    }

    public FolderDto moveFolder(Long id, String newPath) {
        FolderEntity folder = folderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));

        folder.setPath(newPath);
        return FolderDto.from(folderRepository.save(folder));
    }
}
