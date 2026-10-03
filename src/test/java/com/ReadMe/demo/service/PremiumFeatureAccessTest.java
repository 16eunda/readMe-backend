package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.dto.AiInfoResponse;
import com.ReadMe.demo.dto.RecommendationResponse;
import com.ReadMe.demo.exception.PremiumRequiredException;
import com.ReadMe.demo.repository.FileReadLogRepository;
import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.repository.RecRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumFeatureAccessTest {

    @Mock
    private FileRepository fileRepository;
    @Mock
    private FileReadLogRepository readLogRepository;
    @Mock
    private RecRepository recRepository;
    @Mock
    private GeminiService geminiService;
    @Mock
    private SubscriptionService subscriptionService;
    @Mock
    private RankingService rankingService;
    @Mock
    private AnalysisStateService analysisState;

    @Test
    void aiInfoHidesExistingAnalysisWhenSubscriptionIsExpired() {
        FileEntity file = new FileEntity();
        file.setId(1L);
        file.setTitle("book.epub");
        file.setAnalysisStatus("DONE");
        file.setAiGenre("fantasy");
        when(fileRepository.findByIdAndDeviceIdAndUserIsNull(1L, "device-a")).thenReturn(Optional.of(file));
        when(subscriptionService.isPremium(null, "device-a")).thenReturn(false);

        AiAnalysisService service = new AiAnalysisService(fileRepository, geminiService, subscriptionService, analysisState);

        AiInfoResponse response = service.getAiInfo(1L, "device-a", null);

        assertThat(response.getAnalysisStatus()).isEqualTo("PREMIUM_REQUIRED");
        verify(fileRepository, never()).findOwnAnalyzedSameTitle(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void fileRegistrationDoesNotReuseExistingAnalysisWhenSubscriptionIsExpired() {
        FileService service = new FileService(fileRepository, readLogRepository);
        FileEntity file = new FileEntity();
        file.setTitle("book.epub");
        file.setPath("root");
        when(fileRepository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            FileEntity saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        FileEntity saved = service.saveFile(file, "device-a", null);

        assertThat(saved.getAnalysisStatus()).isEqualTo("PENDING");
        verify(fileRepository, never()).findOwnAnalyzedSameTitle(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void recommendationsReturnPremiumRequiredWhenSubscriptionIsExpired() {
        RecService service = new RecService(recRepository, fileRepository, subscriptionService);
        when(subscriptionService.isPremium(null, "device-a")).thenReturn(false);

        RecommendationResponse response = service.getRecommendations(null, "device-a");

        assertThat(response.isPremiumRequired()).isTrue();
        assertThat(response.getQuality()).isEqualTo("PREMIUM_REQUIRED");
        assertThat(response.getRecommendations()).isEmpty();
        verify(fileRepository, never()).countAllByDeviceId("device-a");
    }

    @Test
    void rankingThrowsPremiumRequiredWhenSubscriptionIsExpired() {
        com.ReadMe.demo.controller.RankingController controller =
                new com.ReadMe.demo.controller.RankingController(rankingService, subscriptionService);
        when(subscriptionService.isPremium(null, "device-a")).thenReturn(false);

        assertThatThrownBy(() -> controller.getMonthlyRanking(2025, 1, "device-a", null))
                .isInstanceOf(PremiumRequiredException.class);
    }

    @Test
    void currentMonthRankingIsAvailableWithoutPremium() {
        com.ReadMe.demo.controller.RankingController controller =
                new com.ReadMe.demo.controller.RankingController(rankingService, subscriptionService);
        LocalDate today = LocalDate.now();

        controller.getMonthlyRanking(null, null, "device-a", null);
        controller.getMonthlyRanking(today.getYear(), today.getMonthValue(), "device-a", null);

        verify(subscriptionService, never()).isPremium(null, "device-a");
    }
}
