package com.grandis.nova.preorder.preorder;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.grandis.nova.preorder.preorder.PreorderStatus.CANCELED;
import static com.grandis.nova.preorder.preorder.PreorderStatus.CANCELING;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PAYABLE;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PENDING_SYNC;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.CANCEL_COMPLETED;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.CANCEL_REJECTED;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.CANCEL_REQUESTED;
import static com.grandis.nova.preorder.preorder.PreorderTrigger.REGISTER_CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

/** 상태 · 사건의 모든 조합. 여기 없는 조합은 전이하지 않는다. */
class PreorderStatusTest {

    static final Map<PreorderStatus, Map<PreorderTrigger, PreorderStatus>> ALLOWED = Map.of(
            PENDING_SYNC, Map.of(REGISTER_CONFIRMED, PAYABLE, CANCEL_REQUESTED, CANCELING),
            PAYABLE, Map.of(CANCEL_REQUESTED, CANCELING),
            CANCELING, Map.of(CANCEL_COMPLETED, CANCELED, CANCEL_REJECTED, PAYABLE),
            CANCELED, Map.of());

    static Stream<Arguments> 모든_조합() {
        return Stream.of(PreorderStatus.values()).flatMap(status ->
                Stream.of(PreorderTrigger.values()).map(trigger -> Arguments.of(status, trigger)));
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("모든_조합")
    void 허용한_조합만_다음_상태가_있다(PreorderStatus status, PreorderTrigger trigger) {
        Optional<PreorderStatus> expected = Optional.ofNullable(ALLOWED.get(status).get(trigger));

        assertThat(status.next(trigger)).isEqualTo(expected);
    }
}
