package com.ReadMe.demo.exception;

import com.ReadMe.demo.dto.ApiErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.DateTimeException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FolderNotEmptyException.class)
    public ResponseEntity<ApiErrorResponse> handleFolderNotEmpty(FolderNotEmptyException e) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(
                        "FOLDER_NOT_EMPTY",
                        "폴더 안에 파일 또는 하위 폴더가 있습니다.",
                        e.getInfo()
                ));
    }

    // 기타 예외 처리 추가 가능
    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFileNotFound(FileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(
                        "FILE_NOT_FOUND",
                        e.getMessage(),
                        null
                ));
    }

    @ExceptionHandler(FolderNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleFolderNotFound(FolderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("FOLDER_NOT_FOUND", e.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(
                        "INVALID_ARGUMENT",
                        e.getMessage(),
                        null
                ));
    }

    // 깨진 JSON, 숫자 자리에 문자, 없는 날짜(13월) 등 요청이 잘못된 경우. 아래 RuntimeException 처리로 넘어가면 500 이 된다.
    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class, DateTimeException.class})
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("INVALID_ARGUMENT", "요청 형식이 올바르지 않습니다.", null));
    }

    // 같은 행을 다른 요청이 먼저 지웠거나 바꾼 경우(동시 요청). 서버 장애가 아니다.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleConcurrentModification(OptimisticLockingFailureException e) {
        log.warn("동시 요청 충돌: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("DATA_CONFLICT", "다른 요청이 먼저 처리되었습니다. 다시 시도해 주세요.", null));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedOperation(UnsupportedOperationException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiErrorResponse.of("NOT_IMPLEMENTED", e.getMessage(), null));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("DB 제약 조건 위반: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(
                        "DATA_CONFLICT",
                        "이미 처리된 데이터이거나 현재 상태와 충돌합니다.",
                        null
                ));
    }

    @ExceptionHandler(PremiumRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handlePremiumRequired(PremiumRequiredException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of("PREMIUM_REQUIRED", e.getMessage(), null));
    }

    @ExceptionHandler(SubscriptionOwnedByAnotherAccountException.class)
    public ResponseEntity<ApiErrorResponse> handleSubscriptionOwnedByAnotherAccount(SubscriptionOwnedByAnotherAccountException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of("SUBSCRIPTION_OWNED_BY_OTHER_ACCOUNT", e.getMessage(), null));
    }

    // 예상하지 못한 오류. 원인은 서버 로그에만 남기고, 응답에는 내부 메시지(SQL, 테이블 이름 등)를 싣지 않는다.
    // 예전에는 로그 없이 메시지만 응답으로 내보내서 운영 중 500 의 원인을 알 수 없었다.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> handleRuntimeException(RuntimeException e) {
        log.error("처리하지 못한 서버 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(
                        "INTERNAL_ERROR",
                        "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                        null
                ));
    }

    // 인증 관련 예외 처리
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of("401", e.getMessage(), null));
    }
}
