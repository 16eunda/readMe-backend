package com.ReadMe.demo.service;

import com.ReadMe.demo.domain.FileEntity;
import com.ReadMe.demo.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 파일 AI 분석 상태를 바꾸는 곳.
 *
 * 분석은 AI 응답을 수 초 기다린다. 그동안 앱은 같은 파일의 읽던 위치·폴더·별점을 저장한다.
 * 예전에는 분석 시작 때 읽은 파일 행 전체를 분석이 끝난 뒤 다시 저장해서, 그 사이 저장된 읽던 위치 등이 옛 값으로 되돌아갔다.
 * 그래서 분석 쪽은 분석 상태와 분석 결과 컬럼만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisStateService {

    /**
     * AI 호출은 연결 5초 + 응답 20초가 상한이다(GeminiService).
     * 그보다 충분히 오래 "분석 중"이면 서버가 분석 도중 꺼진 것으로 보고 다른 곳이 다시 분석할 수 있게 한다.
     */
    public static final Duration STALE_PROCESSING = Duration.ofMinutes(10);

    private final FileRepository fileRepository;

    /** 이 파일을 지금 분석할 권한을 가져온다. true 를 받은 한 곳만 AI 를 호출한다. */
    public boolean claim(Long fileId) {
        LocalDateTime now = LocalDateTime.now();
        return fileRepository.claimAnalysis(fileId, now, now.minus(STALE_PROCESSING)) > 0;
    }

    /** 분석을 끝내지 못하고 내려놓는다. status: FAILED(재시도 대상) / PENDING(프리미엄 아님) / LIMIT_EXCEEDED(내일 재시도) */
    public void release(Long fileId, String status) {
        fileRepository.releaseAnalysis(fileId, status);
    }

    /**
     * 분석 결과를 저장한다. 한 트랜잭션 안에서 읽고 바꾸므로(@DynamicUpdate) 실제 UPDATE 에는 분석 컬럼만 들어간다.
     * 분석 중에 파일이 삭제됐거나 다른 곳이 먼저 끝냈으면 저장하지 않는다.
     */
    @Transactional
    public boolean complete(Long fileId, Map<String, String> analysis) {
        FileEntity file = findProcessing(fileId);
        if (file == null) {
            return false;
        }

        file.setAiGenre(analysis.get("genre"));
        file.setAiKeywords(analysis.get("keywords"));
        file.setAiMood(analysis.get("mood"));
        file.setAiContent(analysis.get("info"));
        file.setAiSummary(analysis.get("summary"));
        file.setAiTarget(analysis.get("target"));
        markDone(file);
        return true;
    }

    /** 같은 제목으로 이미 분석된 본인 파일의 결과를 복사한다. (AI 호출 없음) */
    @Transactional
    public boolean completeByCopy(Long fileId, FileEntity source) {
        FileEntity file = findProcessing(fileId);
        if (file == null) {
            return false;
        }

        file.setAiGenre(source.getAiGenre());
        file.setAiKeywords(source.getAiKeywords());
        file.setAiMood(source.getAiMood());
        file.setAiContent(source.getAiContent());
        file.setAiSummary(source.getAiSummary());
        file.setAiTarget(source.getAiTarget());
        markDone(file);
        return true;
    }

    /**
     * 상태는 엔티티가 아니라 컬럼 조회로 확인한다. 요청 동안 영속성 컨텍스트가 열려 있으면(open-in-view)
     * findById 는 요청 초반에 읽은 엔티티를 그대로 돌려주므로, 방금 claim 으로 바꾼 상태가 보이지 않는다.
     * 엔티티가 그렇게 오래된 값이어도 @DynamicUpdate 라 실제 UPDATE 에는 여기서 바꾼 분석 컬럼만 들어간다.
     */
    private FileEntity findProcessing(Long fileId) {
        if (!"PROCESSING".equals(fileRepository.findAnalysisStatusById(fileId).orElse(null))) {
            return null;
        }
        return fileRepository.findById(fileId).orElse(null);
    }

    private static void markDone(FileEntity file) {
        file.setAiAnalyzedAt(LocalDateTime.now());
        file.setAnalysisStatus("DONE");
    }
}
