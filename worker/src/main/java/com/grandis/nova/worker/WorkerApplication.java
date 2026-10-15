package com.grandis.nova.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.Security;

/**
 * SQS 로 받은 외부 예약 등록 · 취소와 럭키드로우 응모 · 추첨을 외부 mock 에 보내고 결과를 이벤트로 돌려준다. DB 를 쓰지 않고 상태는 메시지에 싣고 다닌다.
 * 여러 대가 서로 모르고 동시에 돌고, 중단돼도(FARGATE_SPOT) 메시지는 큐에 남는다. 스케줄 작업은 두지 않는다(batch 로).
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class WorkerApplication {

    /**
     * JVM 이 이름 풀이 결과를 기억하는 시간(초)의 기본값. JVM 기본(30초)이면 새로 뜬 mock 에 요청이 늦게 가고 사라진 주소로 계속 보낸다.
     * JVM 이 처음 이름을 풀기 전에만 먹혀 스프링 설정으로는 바꿀 수 없다 — 바꾸려면 JVM 옵션 -Dsun.net.inetaddr.ttl 로 준다.
     */
    private static final String DEFAULT_DNS_CACHE_SECONDS = "5";

    public static void main(String[] args) {
        if (Security.getProperty("networkaddress.cache.ttl") == null && System.getProperty("sun.net.inetaddr.ttl") == null) {
            Security.setProperty("networkaddress.cache.ttl", DEFAULT_DNS_CACHE_SECONDS);
        }
        SpringApplication.run(WorkerApplication.class, args);
    }
}
