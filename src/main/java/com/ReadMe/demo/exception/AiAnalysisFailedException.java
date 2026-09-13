package com.ReadMe.demo.exception;

/**
 * AI 분석이 실패했음을 호출부에 명확히 알린다.
 * 이전에는 실패 시 "미분류" 기본값을 정상 결과처럼 반환해서
 * analysisStatus=DONE 으로 저장되고 재시도도 되지 않았다.
 */
public class AiAnalysisFailedException extends RuntimeException {
    public AiAnalysisFailedException(String message) {
        super(message);
    }

    public AiAnalysisFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
