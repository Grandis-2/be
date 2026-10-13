package com.grandis.nova.preorder.reconciliation.domain;

/** 두 실행 사이에서 같은 불일치를 가르는 값 */
public record FindingKey(String checkCode, String targetKey) {
}
