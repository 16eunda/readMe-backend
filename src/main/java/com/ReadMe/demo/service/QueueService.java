package com.ReadMe.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final StringRedisTemplate redisTemplate;

    private static final String QUEUE_NAME = "analysisQueue";

    // 작업 추가 (enqueue)
    public void enqueue(Long fileId) {

        redisTemplate.opsForList()
                .leftPush(QUEUE_NAME, fileId.toString());

        System.out.println("📥 Queue 등록 완료: " + fileId);
    }
}