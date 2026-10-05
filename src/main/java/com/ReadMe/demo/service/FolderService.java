package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FolderEntity;
import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.dto.FolderBulkDeleteInfo;
import com.ReadMe.demo.dto.FolderBulkDeleteRequest;
import com.ReadMe.demo.dto.FolderDto;
import com.ReadMe.demo.dto.FolderRequest;
import com.ReadMe.demo.exception.FolderNotEmptyException;
import com.ReadMe.demo.exception.FolderNotFoundException;
import com.ReadMe.demo.exception.UnauthorizedException;
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

        // deviceId 없이 조회하면 device_id IS NULL 조건이 되어, deviceId 없이 저장된 남의 폴더가 보인다.
        if (deviceId == null || deviceId.isBlank()) {
            return List.of();
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
        }

        folder.setDeviceId(deviceId);

        return FolderDto.from(folderRepository.save(folder));
    }

    // 폴더 업데이트 (이름, 경로)
    @Transactional
    public FolderDto updateFolder(Long id, Map<String, Object> body, String deviceId, Authentication authentication) {
        // 소유권 검증. 예전에는 검증 없이 남의 폴더도 수정할 수 있었다.
        FolderEntity folder = findOwnedFolder(id, extractUser(authentication), deviceId);

        if (body.containsKey("name")) {
            if (!(body.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("폴더 이름이 필요합니다.");
            }
            folder.setName(name);
        }
        if (body.containsKey("path")) {
            folder.setPath(validNewParent(folder, body.get("path")));
        }

        return FolderDto.from(folderRepository.save(folder));
    }

    // 폴더 ID로 하위 폴더 ID 수집 (BFS)
    private List<Long> collectFolderIds(FolderEntity root, UserEntity user, String deviceId) {

        // 이미 고리가 생긴 폴더(이동 검사가 없던 때 만들어진 것)에서도 끝나도록 한 번 본 폴더는 다시 보지 않는다.
        // 예전에는 여기서 끝나지 않고 DB 연결을 붙잡은 채 메모리를 다 쓸 때까지 돌았다.
        Set<Long> folderIds = new LinkedHashSet<>();
        Queue<Long> queue = new LinkedList<>();

        queue.add(root.getId());

        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            if (!folderIds.add(currentId)) {
                continue;
            }

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

        return new ArrayList<>(folderIds);
    }

    // 폴더 삭제
    @Transactional
    public void delete(Long folderId, String deviceId, Authentication authentication) {

        UserEntity user = extractUser(authentication);

        // owner 검증
        FolderEntity folder = findOwnedFolder(folderId, user, deviceId);

        // 하위 폴더 id 수집
        List<Long> folderIds = collectFolderIds(folder, user, deviceId);

        // 파일 삭제 (폴더 안 + 하위 폴더 파일)
        fileRepository.deleteFilesWithReadLogs(findOwnedFileIdsInFolders(user, deviceId, folderIds));

        // 폴더 삭제
        if (user != null) {
            folderRepository.deleteByUserAndIdIn(user, folderIds);
        } else {
            folderRepository.deleteByDeviceIdAndUserIsNullAndIdIn(deviceId, folderIds);
        }
    }

    /**
     * 요청자의 폴더만 돌려준다. 없는 폴더와 남의 폴더는 구분하지 않고 404 로 응답한다. (파일과 같은 규칙)
     * 예전에는 없는 폴더는 500, 남의 폴더는 401 이었다. 401 은 앱이 토큰 재발급을 시도하게 만든다.
     */
    private FolderEntity findOwnedFolder(Long folderId, UserEntity user, String deviceId) {
        if (folderId == null) {
            throw new IllegalArgumentException("폴더 id가 필요합니다.");
        }
        // 게스트: deviceId 가 없으면 소유 판단 자체가 불가능하므로 거절한다.
        if (user == null && (deviceId == null || deviceId.isBlank())) {
            throw new UnauthorizedException("인증 정보 없음");
        }

        FolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new FolderNotFoundException(folderId));

        boolean owned = user != null
                ? folder.getUser() != null && user.getId().equals(folder.getUser().getId())
                // 이 기기에서 만들었어도 계정에 연결된 폴더는 로그아웃한 비회원이 바꿀 수 없다.
                : folder.getUser() == null && deviceId.equals(folder.getDeviceId());
        if (!owned) {
            throw new FolderNotFoundException(folderId);
        }
        return folder;
    }

    /**
     * 옮길 위치("root" 또는 폴더 id)를 검사한다.
     * 자기 자신이나 자기 하위 폴더 안으로 옮기면 폴더들이 서로를 부모로 가리키는 고리가 되어 루트에서 닿을 수 없게 되고
     * (안에 든 책까지 사라진 것처럼 보인다), 그 폴더를 지울 때 하위 폴더 수집이 끝나지 않는다.
     * 앱의 이동 화면은 자기 자신과 바로 아래 폴더만 숨기므로 손자 폴더로는 옮길 수 있었다.
     * 새 부모에서 루트 쪽으로 거슬러 올라가며 옮기려는 폴더를 만나는지 본다.
     */
    private String validNewParent(FolderEntity folder, Object requestedPath) {
        if (!(requestedPath instanceof String newPath) || newPath.isBlank()) {
            throw new IllegalArgumentException("옮길 위치(path)가 필요합니다.");
        }

        String movingId = String.valueOf(folder.getId());
        Set<String> visited = new HashSet<>();
        String current = newPath;
        while (current != null && !"root".equals(current) && visited.add(current)) {
            if (current.equals(movingId)) {
                throw new IllegalArgumentException("폴더를 자기 자신이나 하위 폴더 안으로 옮길 수 없습니다.");
            }
            Long parentId = parseFolderId(current);
            if (parentId == null) {
                break;
            }
            current = folderRepository.findById(parentId).map(FolderEntity::getPath).orElse(null);
        }
        return newPath;
    }

    private static Long parseFolderId(String path) {
        try {
            return Long.valueOf(path);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // 파일의 path 는 폴더 id 를 문자열로 담는다.
    private List<Long> findOwnedFileIdsInFolders(UserEntity user, String deviceId, List<Long> folderIds) {
        List<String> paths = folderIds.stream().map(String::valueOf).toList();
        return user != null
                ? fileRepository.findIdsByUserIdAndPathIn(user.getId(), paths)
                : fileRepository.findGuestIdsByDeviceIdAndPathIn(deviceId, paths);
    }

    @Transactional
    public void bulkDelete(FolderBulkDeleteRequest request,
                           String deviceId,
                           Authentication authentication) {

        List<Long> folderIds = request.getFolderIds();
        if (folderIds == null || folderIds.isEmpty()) {
            throw new IllegalArgumentException("삭제할 폴더 id(folderIds)가 필요합니다.");
        }
        boolean force = request.isForce();

        UserEntity user = extractUser(authentication);

        Set<Long> allFolderIds = new HashSet<>();

        // 1️⃣ BFS로 모든 하위 폴더 수집
        for (Long folderId : folderIds) {

            FolderEntity folder = findOwnedFolder(folderId, user, deviceId);

            List<Long> ids = collectFolderIds(folder, user, deviceId);

            allFolderIds.addAll(ids);
        }

        // 삭제할 폴더 ID 리스트 (중복 제거)
        List<Long> deleteFolderIds = new ArrayList<>(allFolderIds);

        // 2️⃣ 파일 개수 계산
        List<Long> fileIds = findOwnedFileIdsInFolders(user, deviceId, deleteFolderIds);
        long fileCount = fileIds.size();

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
        fileRepository.deleteFilesWithReadLogs(fileIds);

        if (user != null) {
            folderRepository.deleteByUserAndIdIn(user, deleteFolderIds);
        } else {
            folderRepository.deleteByDeviceIdAndUserIsNullAndIdIn(deviceId, deleteFolderIds);
        }
    }

    @Transactional
    public FolderDto moveFolder(Long id, String newPath, String deviceId, Authentication authentication) {
        // 소유권 검증. 예전에는 검증 없이 남의 폴더도 이동시킬 수 있었다.
        FolderEntity folder = findOwnedFolder(id, extractUser(authentication), deviceId);

        folder.setPath(validNewParent(folder, newPath));
        return FolderDto.from(folderRepository.save(folder));
    }
}
