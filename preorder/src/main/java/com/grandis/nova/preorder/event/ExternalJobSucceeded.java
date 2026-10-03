package com.grandis.nova.preorder.event;

/**
 * worker 가 외부 등록 · 취소에 성공했다(계약 2.3). 소비자는 payload 를 믿지 않고 작업 원장을 다시 읽는다.
 * 예외는 externalNumber 하나 — 작업 SUCCEEDED 와 같은 트랜잭션에서 한 번만 쓰는 불변 값이다.
 */
public record ExternalJobSucceeded(Long syncJobId, String preorderId, String jobType, String externalNumber) {
}
