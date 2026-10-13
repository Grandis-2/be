package com.grandis.nova.preorder.preorder;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 예약 검색 조건. 값이 없으면 null 이고 그 조건은 걸지 않는다.
 *
 * @param registerJobStatus 외부 등록(REGISTER) 작업의 상태 이름(DEAD_LETTER 등)
 * @param externalReference 외부가 준 예약 번호(정확히 같은 것)
 * @param batchNumber       배송 차수 번호. 상품마다 따로 매기므로 productId 와 함께 준다
 * @param progress          원장 상태만으로 가를 수 없는 진행 조건
 */
public record AdminPreorderSearch(
        PreorderStatus status,
        UUID customerId,
        UUID productId,
        Instant from,
        Instant to,
        String registerJobStatus,
        String externalReference,
        UUID optionId,
        Integer batchNumber,
        Progress progress
) {

    /**
     * @param registerAttempted 외부 등록을 시도한 적이 있는가(등록 작업이 있고 대기 상태가 아님)
     * @param payableAfter      결제 가능 시작이 이 시각보다 뒤
     * @param payableUntil      결제 가능 시작이 이 시각 이전
     * @param paymentStarted    주문이 만들어져 결제가 시작됐는가
     */
    public record Progress(Boolean registerAttempted, Instant payableAfter, Instant payableUntil,
                           Boolean paymentStarted) {

        public static Progress registerAttempted(boolean attempted) {
            return new Progress(attempted, null, null, null);
        }

        public static Progress payableAfter(Instant payableAfter, boolean paymentStarted) {
            return new Progress(null, payableAfter, null, paymentStarted);
        }

        public static Progress payableUntil(Instant payableUntil) {
            return new Progress(null, null, payableUntil, null);
        }
    }
}
