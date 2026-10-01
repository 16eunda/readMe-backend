package com.ReadMe.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@Slf4j
public class QueueService {

    private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
    // 큐에 들어 있는 파일 id. 파일 등록·읽기·주기 점검이 같은 파일을 여러 번 넣어도 한 번만 대기시킨다.
    private final Set<Long> queuedIds = ConcurrentHashMap.newKeySet();
    // getAiInfo 직접 요청용 큐 (일일 제한 제외)
    private final ConcurrentLinkedQueue<Long> priorityQueue = new ConcurrentLinkedQueue<>();

    // 파일 추가 시 자동 분석 (일일 제한 적용). 이미 대기 중인 파일이면 넣지 않고 false 를 돌려준다.
    // 이 큐는 메모리에만 있어 서버가 재시작되면 사라진다. DB 상태를 기준으로 AnalysisBacklogScheduler 가 다시 채운다.
    public boolean enqueue(Long fileId) {
        if (!queuedIds.add(fileId)) {
            return false;
        }
        queue.offer(fileId);
        log.info("📥 Queue 등록 완료: {} (대기: {}건)", fileId, queue.size());
        return true;
    }

    // getAiInfo 직접 요청 (일일 제한 제외)
    public void enqueuePriority(Long fileId) {
        priorityQueue.offer(fileId);
        log.info("📥 Priority Queue 등록 완료: {} (대기: {}건)", fileId, priorityQueue.size());
    }

    // 작업 꺼내기 - priority 먼저 (없으면 null, bypassLimit 여부 함께 반환)
    public Long dequeue() {
        Long fileId = queue.poll();
        if (fileId != null) {
            queuedIds.remove(fileId);
        }
        return fileId;
    }

    public Long dequeuePriority() {
        return priorityQueue.poll();
    }

    // 대기 중인 작업 수
    public int size() {
        return queue.size();
    }
}
