package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.model.OrderItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 읽기 포트의 모양 규칙. 여러 유스케이스가 이 포트를 목으로 흉내 내므로, 메서드 이름이 겹치면(오버로드)
 * any() · null 인자를 쓰는 다른 작업의 테스트가 "reference is ambiguous" 로 컴파일되지 않는다.
 * 각 브랜치에서는 통과하고 합칠 때 깨지는 종류라 규칙으로 막는다.
 */
class OrderReaderContractTest {

    @Test
    void methodNamesAreNotOverloaded() {
        Map<String, Long> countByName = Arrays.stream(OrderReader.class.getMethods())
                .collect(Collectors.groupingBy(Method::getName, Collectors.counting()));

        assertThat(countByName).allSatisfy((name, count) -> assertThat(count).as(name).isEqualTo(1L));
    }

    /** 다른 작업(주문 생성) 테스트가 쓰는 모양 그대로. 이 파일이 컴파일되는 것이 검사다. */
    @Test
    void anyMatcherResolvesEveryItemsMethod() {
        OrderReader reader = mock(OrderReader.class);

        given(reader.findItems(any())).willReturn(List.of());
        given(reader.findItemsByOrderIds(any())).willReturn(List.<OrderItem>of());

        assertThat(reader.findItems(1L)).isEmpty();
        assertThat(reader.findItemsByOrderIds(List.of(1L))).isEmpty();
    }
}
