package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.dto.FileDto;
import com.ReadMe.demo.dto.FileLocationResponse;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceLocationTest {

    @Mock
    private FileRepository fileRepository;
    @Mock
    private FileReadLogRepository readLogRepository;
    @Mock
    private GeminiService geminiService;
    @Mock
    private QueueService queueService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private Authentication authentication;
    @Mock
    private CustomUserDetails userDetails;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(
                fileRepository,
                readLogRepository,
                geminiService,
                queueService,
                subscriptionService
        );
    }

    @Test
    void findsLoggedInFileLocationAndReturnsTargetPage() {
        FileEntity target = targetFile();
        FileDto targetDto = FileDto.from(target);

        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUserId()).thenReturn(7L);
        when(fileRepository.findByIdAndUserId(123L, 7L)).thenReturn(Optional.of(target));
        when(fileRepository.countBeforeDateDescByUserId("folder-7", 7L, target.getDate(), 123L))
                .thenReturn(184L);
        when(fileRepository.findByPathAndUserId(
                org.mockito.ArgumentMatchers.eq("folder-7"),
                org.mockito.ArgumentMatchers.eq(7L),
                any(Pageable.class)
        )).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(2);
            assertSort(pageable, "date", Sort.Direction.DESC);
            return new PageImpl<>(List.of(targetDto), pageable, 200);
        });

        FileLocationResponse response = fileService.findLocation(
                123L, "date,desc", 15, "ignored-device", authentication
        );

        assertEquals(123L, response.getFileId());
        assertEquals("folder-7", response.getPath());
        assertEquals(12, response.getPage());
        assertEquals(4, response.getIndexInPage());
        assertEquals(184L, response.getAbsoluteIndex());
        assertEquals("date,desc", response.getSort());
        assertTrue(response.isHasPrevious());
        assertTrue(response.isHasNext());
        assertEquals(List.of(targetDto), response.getContent());
        verify(fileRepository, never()).findByIdAndDeviceId(any(), any());
    }

    @Test
    void findsGuestFileLocationByDeviceId() {
        FileEntity target = targetFile();
        when(fileRepository.findByIdAndDeviceId(123L, "device-a")).thenReturn(Optional.of(target));
        when(fileRepository.countBeforeRatingAscByDeviceId("folder-7", "device-a", 4, 123L))
                .thenReturn(2L);
        when(fileRepository.findByPathAndDeviceId(
                org.mockito.ArgumentMatchers.eq("folder-7"),
                org.mockito.ArgumentMatchers.eq("device-a"),
                any(Pageable.class)
        )).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(2);
            assertSort(pageable, "rating", Sort.Direction.ASC);
            return new PageImpl<>(List.of(FileDto.from(target)), pageable, 3);
        });

        FileLocationResponse response = fileService.findLocation(
                123L, "rating,asc", 15, "device-a", null
        );

        assertEquals(0, response.getPage());
        assertEquals(2, response.getIndexInPage());
        assertFalse(response.isHasPrevious());
        assertFalse(response.isHasNext());
    }

    @Test
    void rejectsUnsupportedSortAndInvalidSize() {
        assertThrows(IllegalArgumentException.class,
                () -> fileService.findLocation(123L, "title,asc", 15, "device-a", null));
        assertThrows(IllegalArgumentException.class,
                () -> fileService.findLocation(123L, "date,desc", 0, "device-a", null));
    }

    @Test
    void recordReadUpdatesLastReadAtForOwnedDeviceFile() {
        FileEntity target = targetFile();
        when(fileRepository.findByIdAndDeviceId(123L, "device-a")).thenReturn(Optional.of(target));

        fileService.recordRead(123L, "device-a", null);

        assertNotNull(target.getLastReadAt());
    }

    @Test
    void updateProgressDoesNotChangeLastReadAt() {
        FileEntity target = targetFile();
        LocalDateTime previousReadAt = LocalDateTime.of(2026, 6, 10, 21, 0);
        target.setLastReadAt(previousReadAt);
        target.setAnalysisStatus("DONE");
        when(fileRepository.findByIdAndDeviceId(123L, "device-a")).thenReturn(Optional.of(target));
        when(fileRepository.save(target)).thenReturn(target);

        fileService.updateProgress(123L, Map.of("progress", 0.5), "device-a", null);

        assertEquals(previousReadAt, target.getLastReadAt());
    }

    private FileEntity targetFile() {
        FileEntity target = new FileEntity();
        target.setId(123L);
        target.setPath("folder-7");
        target.setTitle("target.epub");
        target.setDate(Instant.parse("2026-06-11T12:00:00Z"));
        target.setRating(4);
        return target;
    }

    private void assertSort(Pageable pageable, String property, Sort.Direction direction) {
        assertEquals(direction, pageable.getSort().getOrderFor(property).getDirection());
        assertEquals(direction, pageable.getSort().getOrderFor("id").getDirection());
    }
}
