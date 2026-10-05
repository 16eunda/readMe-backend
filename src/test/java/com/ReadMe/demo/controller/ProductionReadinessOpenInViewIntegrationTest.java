package com.ReadMe.demo.controller;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 같은 점검을 open-in-view 를 켠 상태로 다시 돌린다. (배포 환경에서 프로필이 바뀌면 켜질 수 있다)
 * 삭제는 DELETE 문으로 하므로, 요청 동안 캐시된 엔티티가 있어도 결과가 같아야 한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:production-readiness-osiv;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.jpa.open-in-view=true"
        }
)
class ProductionReadinessOpenInViewIntegrationTest extends ProductionReadinessIntegrationTest {
}
