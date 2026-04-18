package com.ReadMe.demo.exception;

public class FileNotFoundException extends RuntimeException {

    public FileNotFoundException(String message) {
        super(message);
    }

    public FileNotFoundException(Long fileId) {
        super("파일을 찾을 수 없습니다: ID = " + fileId);
    }
}
