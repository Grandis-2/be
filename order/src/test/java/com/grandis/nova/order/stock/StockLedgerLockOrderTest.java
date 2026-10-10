package com.grandis.nova.order.stock;

import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 행을 잠그는 순서는 option_id 칸(BINARY(16))의 바이트 순서다 — MySQL ORDER BY option_id 와 같아야 확보 · 설정이 서로 반대 순서로 잠그지 않는다.
 * 지금 시각의 v7 id 는 상위 비트가 0 이라 UUID.compareTo(부호 있음)와 같은 순서가 나와 통합 시험으로는 둘을 가르지 못한다 — 상위 비트가 1 인 id 로 본다.
 */
class StockLedgerLockOrderTest {

    static final UUID LOW = UUID.fromString("10000000-0000-7000-8000-000000000000");
    static final UUID HIGH = UUID.fromString("90000000-0000-7000-8000-000000000000");
    static final UUID HIGH_LOW_HALF = UUID.fromString("90000000-0000-7000-0000-000000000001");
    static final UUID HIGH_HIGH_HALF = UUID.fromString("90000000-0000-7000-f000-000000000001");

    @Test
    void lockOrderIsStorageByteOrderNotSignedUuidOrder() {
        List<UUID> ids = List.of(HIGH_HIGH_HALF, HIGH, LOW, HIGH_LOW_HALF);

        List<UUID> byLockOrder = ids.stream().sorted(StockLedger.LOCK_ORDER).toList();
        List<UUID> byBytes = ids.stream().sorted((a, b) -> Arrays.compareUnsigned(UuidBinary.toBytes(a), UuidBinary.toBytes(b))).toList();

        assertThat(byLockOrder).isEqualTo(byBytes).containsExactly(LOW, HIGH_LOW_HALF, HIGH, HIGH_HIGH_HALF);
        assertThat(Stream.of(HIGH, LOW).sorted().toList()).as("대조군 — 부호 있는 비교는 상위 비트 1 을 앞에 둔다").containsExactly(HIGH, LOW);
    }
}
