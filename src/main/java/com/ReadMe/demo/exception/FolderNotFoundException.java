package com.ReadMe.demo.exception;

/**
 * 폴더가 없거나 요청자의 폴더가 아닐 때. 파일(FileNotFoundException)과 같이 둘을 구분하지 않고 404 로 응답한다.
 */
public class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(Long folderId) {
        super("폴더를 찾을 수 없습니다: ID = " + folderId);
    }
}
