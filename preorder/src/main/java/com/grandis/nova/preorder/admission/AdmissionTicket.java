package com.grandis.nova.preorder.admission;

/**
 * 서명 · 상품 · 회원 검증을 통과한 입장권.
 *
 * @param id      토큰 문자열 전체의 SHA-256 16진수 소문자 64자. preorders.admission_ticket_id 에 기록해 1회 소비를 UNIQUE 로 막는다
 * @param expired 만료(서버 시각 오차 포함)가 지났다. 새 접수에는 못 쓰고 같은 접수 키의 재전송 확인에만 쓴다
 */
public record AdmissionTicket(String id, boolean expired) {
}
