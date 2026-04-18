package com.ReadMe.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@Slf4j
public class QueueService {

    private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();

    // 작업 추가
    public void enqueue(Long fileId) {
        queue.offer(fileId);
        log.info("📥 Queue 등록 완료: {} (대기: {}건)", fileId, queue.size());
    }

    // 작업 꺼내기 (없으면 null)
    public Long dequeue() {
        return queue.poll();
    }

    // 대기 중인 작업 수
    public int size() {
        return queue.size();
    }
}