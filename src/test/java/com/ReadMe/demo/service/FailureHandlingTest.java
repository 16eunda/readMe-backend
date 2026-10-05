package com.ReadMe.demo.service;

import com.ReadMe.demo.dto.ApiErrorResponse;
import com.ReadMe.demo.exception.GlobalExceptionHandler;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FailureHandlingTest {

    // DB 장애를 빈 목록(200)으로 바꾸면 앱은 "서재가 비었다"로 보고 캐시까지 빈 목록으로 덮어쓴다.
    // 앱은 오류 응답이면 기존 목록을 유지하므로, 장애는 장애로 전달해야 한다.
    @Test
    void fileListDoesNotTurnDatabaseFailureIntoEmptyLibrary() {
        FileRepository fileRepository = mock(FileRepository.class);
        DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("connection refused");
        when(fileRepository.findByPathAndUserId(anyString(), anyLong(), any(Pageable.class))).thenThrow(dbDown);
        when(fileRepository.findByPathAndDeviceId(anyString(), anyString(), any(Pageable.class))).thenThrow(dbDown);
        FileService fileService = new FileService(fileRepository, mock(FileReadLogRepository.class));

        assertThatThrownBy(() -> fileService.getFilesByPath("root", "device", "1", 0, 15, "date,desc"))
                .isSameAs(dbDown);
        assertThatThrownBy(() -> fileService.getFilesByPath("root", "device", null, 0, 15, "date,desc"))
                .isSameAs(dbDown);
    }

    // 예상하지 못한 오류의 내부 메시지(SQL, 테이블 이름 등)를 응답으로 내보내지 않는다.
    @Test
    void unexpectedErrorDoesNotLeakInternalMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiErrorResponse> response = handler.handleRuntimeException(
                new IllegalStateException("could not execute statement [select * from users where password=?]"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().getMessage()).doesNotContain("select", "users", "password");
    }
}
