package com.grandis.nova.preorder.campaign;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 사전예약 상품 등록으로 처음 만드는 회차 · 배송 차수 값(모듈 공개 API 입력).
 *
 * @param batches 배송 차수 전체. 1번부터 연속이고 상한 없는 마지막 차수가 하나여야 한다
 */
public record CampaignRegistration(Instant opensAt, Instant closesAt, List<Batch> batches) {

    public CampaignRegistration {
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
    }

    /** @param positionTo null 이면 상한 없는 마지막 차수 */
    public record Batch(int batchNumber, long positionFrom, Long positionTo, LocalDate estimatedShipStart,
                        LocalDate estimatedShipEnd) {

        public Batch {
            Objects.requireNonNull(estimatedShipStart, "estimatedShipStart");
            Objects.requireNonNull(estimatedShipEnd, "estimatedShipEnd");
        }
    }
}
