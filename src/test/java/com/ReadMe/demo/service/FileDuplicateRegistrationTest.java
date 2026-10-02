package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileDuplicateRegistrationTest {

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
    void duplicateCheckIsDeviceScopedButRegistrationStillAllowsDuplicate() {
        when(fileRepository.existsByDeviceIdAndUserIsNullAndTitleAndPath("device-a", "book.epub", "root"))
                .thenReturn(true);
        when(fileRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            FileEntity saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        assertEquals(true, fileService.isDuplicate("device-a", "book.epub", "root", null));
        FileEntity saved = fileService.saveFile(file(), "device-a", null);

        assertEquals(1L, saved.getId());
        verify(fileRepository).saveAndFlush(any());
        verify(fileRepository, never()).findOwnAnalyzedSameTitle(any(), any(), any(), any());
    }

    @Test
    void savesOwnershipAndPendingStatusBeforeAiProcessing() {
        FileEntity file = file();
        when(fileRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            FileEntity saved = invocation.getArgument(0);
            assertEquals("device-a", saved.getDeviceId());
            assertEquals("PENDING", saved.getAnalysisStatus());
            saved.setId(1L);
            return saved;
        });

        FileEntity saved = fileService.saveFile(file, "device-a", null);

        assertEquals("device-a", saved.getDeviceId());
        assertEquals("PENDING", saved.getAnalysisStatus());
        verify(fileRepository, never()).findOwnAnalyzedSameTitle(any(), any(), any(), any());
    }

    // 구독자가 등록해도 여기서는 분석하지 않는다. 분석은 AnalysisBacklogScheduler 가 맡는다.
    @Test
    void registrationLeavesAnalysisToBackgroundEvenForPremium() {
        when(fileRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            FileEntity saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        FileEntity saved = fileService.saveFile(file(), "device-a", null);

        assertEquals(1L, saved.getId());
        assertEquals("PENDING", saved.getAnalysisStatus());
        verifyNoInteractions(subscriptionService, queueService, geminiService);
        verify(fileRepository, never()).findOwnAnalyzedSameTitle(any(), any(), any(), any());
    }

    private FileEntity file() {
        FileEntity file = new FileEntity();
        file.setTitle("book.epub");
        file.setPath("root");
        file.setDate(Instant.parse("2026-06-13T00:00:00Z"));
        return file;
    }
}
