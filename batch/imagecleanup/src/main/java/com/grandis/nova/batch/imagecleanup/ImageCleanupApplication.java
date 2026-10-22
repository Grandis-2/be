package com.grandis.nova.batch.imagecleanup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 안 쓰는 사진 정리 — 기준 시간이 지난 UNUSED 사진(images)의 행과 S3 객체를 지운다.
 *
 * Scheduler 가 매일 05:00(KST)에 태스크로 띄운다. 기동하면 한 번 정리하고(ImageCleanupRunner) 결과를 종료 코드로 남기며 끝난다 — 앱
 * 안에 주기 작업을 두면 태스크가 뜬 뒤 그 시각을 기다려 하루 늦게 돌고, 끝나지 않는 태스크가 매일 쌓인다. 손으로 띄운 태스크와 겹친
 * 실행은 MySQL 이름 잠금(ImageCleanupLock)이 막아, 잠금을 못 잡은 쪽은 건너뛴다.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class ImageCleanupApplication {

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /**
     * 앱을 띄워 정리를 한 번 하고, 컨텍스트를 닫은 뒤 실행기가 남긴 종료 코드를 돌려준다. 기동 · 정리가 실패하면 1 이다(원인은 Spring 이
     * 로그로 남긴다) — 비데몬 스레드가 남아 있어도 진입점이 끝내 태스크가 서 있지 않게 한다. 시험은 이 메서드로 종료 코드를 본다.
     */
    static int execute(String... args) {
        try {
            return SpringApplication.exit(new SpringApplicationBuilder(ImageCleanupApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(args));
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
