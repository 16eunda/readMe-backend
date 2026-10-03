package com.ReadMe.demo.service;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 같은 시나리오를 open-in-view 를 켠 상태로 다시 돌린다.
 * 지금 설정(dev 프로필)은 꺼져 있지만, 배포 환경에서 프로필이 바뀌어 켜지면 요청 하나 동안 엔티티가 캐시된다.
 * 그때도 AI 정보 조회가 방금 저장한 분석 결과를 돌려주는지 확인한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:analysis-lifecycle-osiv;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.jpa.open-in-view=true"
        }
)
class AnalysisLifecycleOpenInViewIntegrationTest extends AnalysisLifecycleIntegrationTest {
}
