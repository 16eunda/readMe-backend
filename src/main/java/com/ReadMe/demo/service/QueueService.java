package com.ReadMe.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@Slf4j
public class QueueService {

    private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
    // getAiInfo 직접 요청용 큐 (일일 제한 제외)
    private final ConcurrentLinkedQueue<Long> priorityQueue = new ConcurrentLinkedQueue<>();

    // 파일 추가 시 자동 분석 (일일 제한 적용)
    public void enqueue(Long fileId) {
        queue.offer(fileId);
        log.info("📥 Queue 등록 완료: {} (대기: {}건)", fileId, queue.size());
    }

    // getAiInfo 직접 요청 (일일 제한 제외)
    public void enqueuePriority(Long fileId) {
        priorityQueue.offer(fileId);
        log.info("📥 Priority Queue 등록 완료: {} (대기: {}건)", fileId, priorityQueue.size());
    }

    // 작업 꺼내기 - priority 먼저 (없으면 null, bypassLimit 여부 함께 반환)
    public Long dequeue() {
        return queue.poll();
    }

    public Long dequeuePriority() {
        return priorityQueue.poll();
    }

    // 대기 중인 작업 수
    public int size() {
        return queue.size();
    }
}