package com.ReadMe.demo.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 외부 API 호출용 공용 RestTemplate.
 * 타임아웃 없는 호출은 요청 스레드를 무한 점유해 서버 전체를 멈추게 하므로 반드시 설정한다.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate externalApiRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
