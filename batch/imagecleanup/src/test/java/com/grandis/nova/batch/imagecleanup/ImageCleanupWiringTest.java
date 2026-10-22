package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.grandis.nova.batch.imagecleanup.support.ImageCleanupIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 기동하면 정리를 한 번 하고 그 결과를 종료 코드로 남긴다 — 앱을 실제 서비스 · DB 로 띄워 실행기가 도는지 본다. dry-run(기본값)이라
 * 아무것도 지우지 않는다. 결과별 종료 코드는 ImageCleanupRunnerTest · ImageCleanupExitTest, 속성이 없을 때 켜지는 경로는
 * ImageCleanupRunnerConditionTest 가 본다. 실행기를 켠 이 컨텍스트가 캐시에 남아 DB 연결을 쥐지 않게 시험이 끝나면 닫는다.
 */
@TestPropertySource(properties = "nova.batch.image-cleanup.run-on-startup=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImageCleanupWiringTest extends ImageCleanupIntegrationTest {

    // 기동 때 한 번 부른 기록을 메서드가 끝나도 지우지 않는다(기본값은 메서드마다 비운다)
    @MockitoSpyBean(reset = MockReset.NONE)
    ImageCleanupService service;

    @Autowired
    ApplicationContext context;

    @Test
    void 기동하면_한_번_정리하고_종료_코드_0_을_남긴다() {
        verify(service).run();
        // 진입점이 SpringApplication.exit 로 모으는 종료 코드 — 실행기 하나뿐이다
        assertThat(context.getBeansOfType(ExitCodeGenerator.class).values())
                .singleElement()
                .extracting(ExitCodeGenerator::getExitCode)
                .isEqualTo(0);
    }
}
