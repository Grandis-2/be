package com.grandis.nova.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order 의 주기 작업(@Scheduled — 결제 기한 만료)을 켠다. 공통 모듈은 스케줄링을 켜지 않는다(아웃박스 릴레이는 전용 실행기) — 켜기는 서비스가 정한다.
 * 기본 스케줄러(스레드 1개)를 쓴다 — 주기 작업이 하나라 겹치지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OrderSchedulingConfig {
}
