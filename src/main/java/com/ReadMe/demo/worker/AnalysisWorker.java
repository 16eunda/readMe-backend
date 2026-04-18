package com.ReadMe.demo.worker;

import com.ReadMe.demo.service.AnalysisService;
import com.ReadMe.demo.service.QueueService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalysisWorker {

    private final QueueService queueService;
    private final AnalysisService analysisService;
    private volatile boolean running = true;

    @PostConstruct
    public void startWorker() {
        log.info("🚀 Analysis Worker 시작");

        new Thread(() -> {
            while (running) {
                try {
                    Long fileId = queueService.dequeue();

                    if (fileId != null) {
                        log.info("🎯 작업 가져옴: {}", fileId);
                        analysisService.analyze(fileId);
                    } else {
                        // 큐가 비어있으면 5초 대기
                        Thread.sleep(5000);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Worker 오류", e);
                }
            }
        }).start();
    }

    @PreDestroy
    public void stopWorker() {
        log.info("Worker 종료 시작");
        running = false;
    }
}
