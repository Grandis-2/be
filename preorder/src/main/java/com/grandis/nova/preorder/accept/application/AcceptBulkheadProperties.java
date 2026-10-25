package com.grandis.nova.preorder.accept.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 접수 격벽. 같은 상품의 접수는 회차 행 잠금에 한 줄로 서고 기다리는 동안 커넥션을 쥔다 — 줄을 커넥션 밖(메모리)에 세운다.
 *
 * @param productPermits 상품마다 동시에 트랜잭션에 들어가는 접수 수. 4 — 잠금을 쥔 1 + 잠그기 전 쓰기(약 6왕복, 잠금 구간 3왕복의
 *                       두 배)를 하는 2 + 풀리면 바로 이어 받을 1. 더 들여보내도 처리량은 그대로고 잠금을 기다리며 커넥션만 쥔다.
 *                       인스턴스 상한(풀 10 의 절반 5)보다 작아 한 상품이 다른 상품의 접수를 막지 않는다
 * @param poolShare      인스턴스 전체 동시 접수 상한을 커넥션 풀의 이 비율로 정한다. 0.5 면 풀 10 중 5 — 나머지를 아웃박스 발행 ·
 *                       SQS 소비기 · 조회가 쓴다(풀은 하나다)
 * @param instanceLimit  전체 상한을 직접 준다. 0 이면 poolShare 로 계산한다. 풀 크기보다 작아야 한다 — 같거나 크면 접수가 풀을
 *                       다 쥘 수 있어 격벽이 조회를 지키지 못한다
 * @param maxWait        두 허가를 합쳐 기다리는 상한. 커넥션 대기 상한(2초)과 비슷하게 짧게 두고 503 + Retry-After 로 돌려보낸다 —
 *                       입장권은 발급 뒤 90~120초 쓸 수 있고 거절된 접수는 입장권을 쓰지 않아 다시 보내면 된다
 * @param retryAfterMin  거절 응답의 Retry-After 아래 끝
 * @param retryAfterMax  위 끝. 거절된 요청이 같은 초에 한꺼번에 돌아오지 않게 그 사이로 흩는다
 */
@ConfigurationProperties("nova.accept.bulkhead")
record AcceptBulkheadProperties(
        @DefaultValue("4") int productPermits,
        @DefaultValue("0.5") double poolShare,
        @DefaultValue("0") int instanceLimit,
        @DefaultValue("3s") Duration maxWait,
        @DefaultValue("1s") Duration retryAfterMin,
        @DefaultValue("3s") Duration retryAfterMax
) {

    AcceptBulkheadProperties {
        if (productPermits < 1) {
            throw new IllegalArgumentException("nova.accept.bulkhead.product-permits 는 1 이상이어야 한다");
        }
        // 1 이면 접수가 풀을 다 쥘 수 있다(직접 준 상한과 같은 규칙)
        if (!(poolShare > 0 && poolShare < 1)) {
            throw new IllegalArgumentException("nova.accept.bulkhead.pool-share 는 0 초과 1 미만이어야 한다");
        }
        if (instanceLimit < 0) {
            throw new IllegalArgumentException("nova.accept.bulkhead.instance-limit 은 0 이상이어야 한다");
        }
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("nova.accept.bulkhead.max-wait 는 0 이상이어야 한다");
        }
        if (retryAfterMin.toSeconds() < 1 || retryAfterMax.compareTo(retryAfterMin) < 0) {
            throw new IllegalArgumentException(
                    "nova.accept.bulkhead.retry-after-min 은 1초 이상, retry-after-max 는 그보다 작지 않아야 한다");
        }
    }

    /** 전체 상한. 직접 준 값이 없으면 풀 크기의 poolShare(내림, 최소 1). */
    int instanceLimit(int poolSize) {
        if (instanceLimit > 0) {
            if (instanceLimit >= poolSize) {
                throw new IllegalArgumentException("nova.accept.bulkhead.instance-limit(" + instanceLimit
                        + ") 은 커넥션 풀 크기(" + poolSize + ")보다 작아야 한다");
            }
            return instanceLimit;
        }
        return Math.max(1, (int) Math.floor(poolSize * poolShare));
    }
}
