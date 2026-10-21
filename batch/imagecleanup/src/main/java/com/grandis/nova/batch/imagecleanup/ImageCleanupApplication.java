package com.grandis.nova.batch.imagecleanup;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 안 쓰는 사진 정리 — 기준 시간이 지난 UNUSED 사진(images)의 행과 S3 객체를 지운다.
 *
 * ECS desired 1 로 띄운다. 겹친 실행은 MySQL 이름 잠금(ImageCleanupLock)이 막아, 잠금을 못 잡은 쪽은 그 회차를 건너뛴다.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
@EnableScheduling
public class ImageCleanupApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(ImageCleanupApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
