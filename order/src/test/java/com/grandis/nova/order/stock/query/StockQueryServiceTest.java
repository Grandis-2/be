package com.grandis.nova.order.stock.query;

import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.stock.query.StockQueryService.AvailableStock;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 순서 · 중복 · 빠짐 · 0 자르기. DB 와 HTTP 는 InternalStockApiTest 가 본다. */
class StockQueryServiceTest {

    static final UUID A = TestIds.id(1);
    static final UUID B = TestIds.id(2);
    static final UUID C = TestIds.id(3);

    StockReader reader = mock(StockReader.class);
    StockQueryService service = new StockQueryService(reader);

    /** 읽기 포트는 option_id 오름차순으로 돌려주지만 응답은 요청 순서다. 같은 id 는 한 번만 묻는다. */
    @Test
    void keepsRequestOrderAndAsksEachIdOnce() {
        given(reader.findByOptionIds(List.of(C, A, B))).willReturn(List.of(
                new StockLevel(A, 3, 1, 1), new StockLevel(C, 5, 0, 0)));

        assertThat(service.findAvailable(List.of(C, A, C, B)))
                .containsExactly(new AvailableStock(C, 5), new AvailableStock(A, 1));
        verify(reader).findByOptionIds(List.of(C, A, B));
    }

    /** 표의 CHECK 가 막는 상태지만, 와도 계약(0 이상)을 깨지 않는다. */
    @Test
    void negativeAvailableIsZero() {
        given(reader.findByOptionIds(List.of(A))).willReturn(List.of(new StockLevel(A, 1, 2, 0)));

        assertThat(service.findAvailable(List.of(A))).containsExactly(new AvailableStock(A, 0));
    }
}
