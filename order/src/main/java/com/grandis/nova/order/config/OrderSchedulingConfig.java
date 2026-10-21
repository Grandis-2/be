package com.grandis.nova.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order 의 주기 작업(@Scheduled — 결제 기한 만료 · 드로우 추첨 · 드로우 반영 지연 검사 · 재고 장부 대조 · 재고 지표 · 주문 상태 지표 갱신)을 켠다. 공통 모듈은 스케줄링을 켜지 않는다
 * (아웃박스 릴레이는 전용 실행기) — 켜기는 서비스가 정한다.
 * 기본 스케줄러(스레드 1개)를 쓴다 — 작업끼리 겹치지 않는 대신, 하나가 길면 나머지가 그만큼 밀린다. 가장 긴 것은 새벽 재고 장부 대조
 * (문장 상한 2분)다. 그동안 기한 만료 · 추첨이 늦어도 정합은 그대로다 — 결제 시작은 기한을 스스로 보고, 추첨은 늦게 할 뿐이다.
 * 잠금을 기다릴 수 있는 작업은 기다리지 않고 건너뛴다(반영 지연 검사 — DrawSettlement.reportStalled) — 남이 쥔 잠금에 스레드가 묶이지 않게.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class OrderSchedulingConfig {
}
