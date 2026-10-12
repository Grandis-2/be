package com.grandis.nova.batch;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정합성 검사 — be 예약 · Mock 원장 · be 내부 데이터를 대조해 reconciliation_runs · reconciliation_findings 에 남긴다.
 *
 * ECS desired 1 로 고정한다. 대기 · 실행 중인 실행은 DB 가 하나로 막지만(active_marker), 여러 개가 뜨면
 * 기동 정리가 다른 인스턴스의 실행 중을 실패로 바꾼다. 만료 스캔은 batch 에 넣지 않는다(10-12 결정).
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
@EnableScheduling
public class BatchApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(BatchApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
