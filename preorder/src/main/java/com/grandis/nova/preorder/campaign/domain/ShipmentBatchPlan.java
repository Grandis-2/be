package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 배송 차수 설정 한 벌. 오픈 전에 전체를 교체하며, 여기서 구간 규칙을 검사한다.
 *
 * DB 는 "최대 1개" 까지만 막는다(UNIQUE). 1번부터 연속인지, 구간이 이어지는지,
 * 상한 없는 마지막 차수가 정확히 하나인지는 여기서 본다 — 그래야 어떤 순번이든 속할 차수가 하나다.
 */
public record ShipmentBatchPlan(List<Line> lines) {

    /** 첫 차수는 1번 순번부터 시작한다. */
    static final long FIRST_POSITION = 1;

    public ShipmentBatchPlan {
        lines = List.copyOf(lines);
        validate(lines);
    }

    /** 설정 한 줄. positionTo 가 null 이면 상한 없는 마지막 차수다. */
    public record Line(int batchNumber, long positionFrom, Long positionTo,
                       LocalDate estimatedShipStart, LocalDate estimatedShipEnd) {
    }

    public List<ShipmentBatch> toBatches(Long productId) {
        return lines.stream()
                .map(line -> ShipmentBatch.of(productId, line.batchNumber(), line.positionFrom(), line.positionTo(),
                        line.estimatedShipStart(), line.estimatedShipEnd()))
                .toList();
    }

    private static void validate(List<Line> lines) {
        if (lines.isEmpty()) {
            throw invalid("차수가 하나도 없습니다.");
        }
        Line previous = null;
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            requireNumber(line, index);
            requireRange(line);
            requirePosition(line, previous);
            requireShipWindow(line);
            previous = line;
        }
        requireExactlyOneOpenEnded(lines);
    }

    private static void requireNumber(Line line, int index) {
        if (line.batchNumber() != index + 1) {
            throw invalid("batch %d 의 batchNumber(%d)가 1부터 연속이 아닙니다."
                    .formatted(index + 1, line.batchNumber()));
        }
    }

    private static void requireRange(Line line) {
        if (line.positionTo() != null && line.positionTo() < line.positionFrom()) {
            throw invalid("batch %d positionTo(%d) < positionFrom(%d)"
                    .formatted(line.batchNumber(), line.positionTo(), line.positionFrom()));
        }
    }

    private static void requirePosition(Line line, Line previous) {
        if (previous == null) {
            if (line.positionFrom() != FIRST_POSITION) {
                throw invalid("batch 1 positionFrom(%d)은 %d 이어야 합니다."
                        .formatted(line.positionFrom(), FIRST_POSITION));
            }
            return;
        }
        if (previous.positionTo() == null) {
            throw invalid("batch %d 는 상한 없는 차수 뒤에 올 수 없습니다.".formatted(line.batchNumber()));
        }
        if (line.positionFrom() != previous.positionTo() + 1) {
            throw invalid("batch %d positionFrom(%d) != batch %d positionTo(%d) + 1"
                    .formatted(line.batchNumber(), line.positionFrom(), previous.batchNumber(),
                            previous.positionTo()));
        }
    }

    private static void requireShipWindow(Line line) {
        if (line.estimatedShipEnd().isBefore(line.estimatedShipStart())) {
            throw invalid("batch %d 의 배송 예정 종료일이 시작일보다 앞섭니다.".formatted(line.batchNumber()));
        }
    }

    private static void requireExactlyOneOpenEnded(List<Line> lines) {
        long openEnded = lines.stream().filter(line -> line.positionTo() == null).count();
        if (openEnded != 1) {
            throw invalid("상한 없는 마지막 차수(positionTo = null)가 %d 개입니다. 정확히 1개여야 합니다."
                    .formatted(openEnded));
        }
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(PreorderErrorCode.SHIPMENT_BATCH_INVALID, Map.of("reason", reason));
    }
}
