package com.ReadMe.demo.worker;

import com.ReadMe.demo.service.AnalysisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class AnalysisWorker {

    private final StringRedisTemplate redisTemplate;
    private final AnalysisService analysisService;
    private volatile boolean running = true; // ⭐ 종료 스위치

    private static final String QUEUE_NAME = "analysisQueue";

    @PostConstruct
    public void startWorker() {

        System.out.println("🚀 Analysis Worker 시작");

        new Thread(() -> {

            while (running) {

                try {
                    // queue에서 작업 하나 가져오기 (5초 마다)
                    String fileId =
                            redisTemplate.opsForList()
                                    .rightPop(QUEUE_NAME, 5, TimeUnit.SECONDS);

                    if (fileId != null) {

                        System.out.println("🎯 작업 가져옴: " + fileId);

                        analysisService.analyze(
                                Long.parseLong(fileId)
                        );
                    }

                } catch (Exception e) {
                    System.err.println("Worker 오류");
                    e.printStackTrace();
                }
            }

        }).start();
    }

    @PreDestroy
    public void stopWorker() {
        System.out.println("Worker 종료 시작");
        running = false;
    }
}

//@PostConstruct 실행
//↓
//Worker thread 생성
//↓
//5초마다 Queue 확인
//↓
//작업 발견 → analyze 실행