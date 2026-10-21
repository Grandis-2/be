package com.grandis.nova.batch.reconciliation.check;

/**
 * 불일치가 상한을 넘었다. 그 실행은 판정 불가로 끝내고 결과를 남기지 않는다 — Mock 주소가 다른 환경을 가리키거나 원장이 비어 예약 전부가
 * 불일치가 되는 경우를 메모리 · 완료 트랜잭션이 예약 수만큼 커지기 전에 끊는다. 메시지는 실행의 실패 사유로 화면에 나간다.
 */
public class TooManyFindings extends RuntimeException {

    TooManyFindings(int max) {
        super("불일치가 상한(" + max + "건)을 넘음 — Mock 주소 · 데이터 설정 확인 필요");
    }
}
