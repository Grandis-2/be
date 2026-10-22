package com.grandis.nova.preorder.preorder;

/**
 * 화면 단계 하나를 원장 조건으로 푼 것. 회원 목록의 단계 조건 · 단계별 건수가 쓴다.
 *
 * @param status   원장 상태
 * @param progress 원장 상태만으로 가를 수 없을 때의 진행 조건. 상태만으로 정해지면 null
 */
public record StageCondition(PreorderStatus status, AdminPreorderSearch.Progress progress) {
}
