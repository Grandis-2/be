package com.grandis.nova.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SQS 로 받은 외부 예약 등록 · 취소를 외부 mock 에 보내고 결과를 이벤트로 돌려준다. DB 를 쓰지 않고 상태는 메시지에 싣고 다닌다.
 * 여러 대가 서로 모르고 동시에 돌고, 중단돼도(FARGATE_SPOT) 메시지는 큐에 남는다. 스케줄 작업은 두지 않는다(batch 로).
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
