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
 * 분석이 필요한데 큐에 없는 파일을 주기적으로 큐에 다시 넣는다.
 *
 * 분석 요청은 파일 등록·읽기 때만 생기고 큐는 메모리에만 있어서, 예전에는 다음 파일이 영원히 분석되지 않았다.
 * - 무료일 때 등록하고 아직 열지 않은 파일 (구독해도 분석 대상이 되지 않았다)
 * - 서버 재시작으로 큐에서 사라진 파일, 분석 도중 서버가 꺼져 "분석 중"으로 남은 파일
 * - 하루 자동 분석 한도를 넘긴 파일
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
