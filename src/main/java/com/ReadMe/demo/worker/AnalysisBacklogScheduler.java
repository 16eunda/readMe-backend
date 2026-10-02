package com.ReadMe.demo.worker;

import com.ReadMe.demo.repository.FileRepository;
import com.ReadMe.demo.service.AnalysisStateService;
import com.ReadMe.demo.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 자동 분석의 유일한 입구. 1분마다 구독자의 분석이 필요한 파일을 골라 큐에 넣는다.
 * (파일 등록·읽기는 분석을 요청하지 않는다. 사용자가 AI 정보 화면을 열 때만 그 자리에서 바로 분석한다.)
 *
 * 고르는 파일
 * - 새로 등록했거나 무료일 때 등록해 아직 분석하지 않은 파일
 * - 서버 재시작으로 큐에서 사라진 파일, 분석 도중 서버가 꺼져 "분석 중"으로 남은 파일
 * - 실패했거나 하루 자동 분석 한도를 넘긴 파일 (다음 날 다시)
 * 최근 읽은 책 → 최근 등록한 책 순으로 넣으므로, 하루 한도가 지금 읽는 책과 새 책에 먼저 쓰인다.
 *
 * 워커는 한 번에 한 파일씩 분석하고 계정당 하루 자동 분석 한도도 그대로라서, 구독 직후 파일이 많아도 AI 호출이 한꺼번에 몰리지 않는다.
 * 같은 파일을 여러 번 골라도 큐는 한 번만 담고, 분석 직전 선점(claim)한 한 곳만 분석하므로 중복 분석되지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisBacklogScheduler {

    private static final int BATCH_SIZE = 200;

    private final FileRepository fileRepository;
    private final QueueService queueService;

    @Scheduled(
            initialDelayString = "${analysis.backlog.initial-delay-ms:30000}",
            fixedDelayString = "${analysis.backlog.interval-ms:60000}"
    )
    public void enqueueBacklog() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> fileIds = fileRepository.findAnalysisBacklogIds(
                Instant.now(),
                now.toLocalDate().atStartOfDay(),
                now.minus(AnalysisStateService.STALE_PROCESSING),
                PageRequest.of(0, BATCH_SIZE)
        );

        int added = 0;
        for (Long fileId : fileIds) {
            if (queueService.enqueue(fileId)) {
                added++;
            }
        }
        if (added > 0) {
            log.info("분석이 필요한 파일 {}건을 큐에 다시 넣었습니다.", added);
        }
    }
}
