package com.grandis.nova.batch.imagecleanup;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동하면 정리를 한 번 하고 결과를 종료 코드로 남긴다. 진입점이 SpringApplication.exit 로 받아 프로세스를 끝낸다.
 * 배치 실패 경보는 종료 코드가 0 · 143(SIGTERM) 이 아니면 울린다.
 * <ul>
 *   <li>0 — 정리를 마쳤다. 잠금 때문에 건너뛰었거나 S3 삭제 실패로 멈춘 것도 다음 실행이 이어 지우므로 0 이다</li>
 *   <li>{@link #BLOCKED_EXIT_CODE} — 삭제 대기가 며칠째 남아 정리가 막혀 있다</li>
 *   <li>1 — 기동 · 정리 중 예외(진입점이 받는다)</li>
 * </ul>
 * 빈을 다 만든 뒤에 돈다 — AOT 학습 · 검증은 그 전(-Dspring.context.exit=onRefresh)에 끝나 정리가 돌지 않는다.
 * 시험은 nova.batch.image-cleanup.run-on-startup=false 로 끄고 서비스를 직접 부른다.
 */
@Component
@ConditionalOnProperty(prefix = "nova.batch.image-cleanup", name = "run-on-startup", havingValue = "true",
        matchIfMissing = true)
class ImageCleanupRunner implements ApplicationRunner, ExitCodeGenerator {

    /** 정리가 막혀 있을 때의 종료 코드. 예외(1) · SIGTERM(143) 과 겹치지 않게 둔다 */
    static final int BLOCKED_EXIT_CODE = 2;

    private final ImageCleanupService service;
    private int exitCode;

    ImageCleanupRunner(ImageCleanupService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = service.run() == ImageCleanupService.Result.BLOCKED ? BLOCKED_EXIT_CODE : 0;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
