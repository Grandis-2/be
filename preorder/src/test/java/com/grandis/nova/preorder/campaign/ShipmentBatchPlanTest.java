package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchPlan;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 차수 구간 규칙. DB 는 "최대 1개" 까지만 막으므로 나머지는 여기서 본다. */
class ShipmentBatchPlanTest {

    static final LocalDate SHIP_START = LocalDate.of(2026, 11, 1);
    static final LocalDate SHIP_END = LocalDate.of(2026, 11, 7);

    @Test
    void 이어지는_구간과_상한_없는_마지막_차수면_통과한다() {
        ShipmentBatchPlan plan = new ShipmentBatchPlan(List.of(
                line(1, 1, 1000L), line(2, 1001, 5000L), line(3, 5001, null)));

        assertThat(plan.toBatches(UUID.randomUUID()))
                .extracting(ShipmentBatch::getBatchNumber, ShipmentBatch::getPositionFrom, ShipmentBatch::getPositionTo)
                .containsExactly(tuple(1, 1L, 1000L), tuple(2, 1001L, 5000L), tuple(3, 5001L, null));
    }

    @Test
    void 첫_차수는_1번_순번부터다() {
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(line(1, 10, null))))
                .satisfies(invalid("positionFrom(10)은 1 이어야"));
    }

    @Test
    void 차수_번호는_1부터_연속이다() {
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(line(2, 1, null))))
                .satisfies(invalid("1부터 연속이 아닙니다"));
    }

    @Test
    void 구간에_공백이나_중첩이_있으면_거부한다() {
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(line(1, 1, 1000L), line(2, 900, null))))
                .satisfies(invalid("positionFrom(900)"));
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(line(1, 1, 1000L), line(2, 1002, null))))
                .satisfies(invalid("positionFrom(1002)"));
    }

    @Test
    void 상한_없는_차수는_정확히_하나이고_마지막이다() {
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(line(1, 1, 1000L), line(2, 1001, 2000L))))
                .satisfies(invalid("0 개입니다"));
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(line(1, 1, null), line(2, 1001, null))))
                .satisfies(invalid("상한 없는 차수 뒤에 올 수 없습니다"));
    }

    @Test
    void 구간과_배송_예정일이_뒤집히면_거부한다() {
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(
                new ShipmentBatchPlan.Line(1, 100, 10L, SHIP_START, SHIP_END))))
                .satisfies(invalid("positionTo(10) < positionFrom(100)"));
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of(
                new ShipmentBatchPlan.Line(1, 1, null, SHIP_END, SHIP_START))))
                .satisfies(invalid("종료일이 시작일보다 앞섭니다"));
    }

    @Test
    void 차수가_비어_있으면_거부한다() {
        assertThatThrownBy(() -> new ShipmentBatchPlan(List.of()))
                .satisfies(invalid("차수가 하나도 없습니다"));
    }

    private static ShipmentBatchPlan.Line line(int batchNumber, long positionFrom, Long positionTo) {
        return new ShipmentBatchPlan.Line(batchNumber, positionFrom, positionTo, SHIP_START, SHIP_END);
    }

    private static ThrowingConsumer<Throwable> invalid(String reason) {
        return thrown -> {
            assertThat(thrown).isInstanceOf(BusinessException.class);
            BusinessException business = (BusinessException) thrown;
            assertThat(business.errorCode()).isEqualTo(PreorderErrorCode.SHIPMENT_BATCH_INVALID);
            assertThat(business.details()).hasEntrySatisfying("reason",
                    value -> assertThat(value.toString()).contains(reason));
        };
    }
}
