package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.PaymentConfirmSlots;
import com.grandis.nova.order.client.payment.PaymentConfirmation;
import com.grandis.nova.order.client.payment.PaymentConfirmer;
import com.grandis.nova.order.client.payment.PaymentPreparer;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.EntryTransition;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 응모비 결제의 경합 · 잠금 실패 갈래 — MySQL 로는 결정적으로 만들기 어려운 순간을 저장소 대역으로 고정한다(mock 트랜잭션 매니저).
 */
@ExtendWith(OutputCaptureExtension.class)
class DrawEntryPaymentUnitTest {

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID DRAW = TestIds.id(1);
    static final UUID MEMBER = TestIds.id(2);
    static final String WINDOW = "draw_unit_window";
    static final DrawCampaign CAMPAIGN = new DrawCampaign(DRAW, TestIds.id(3), TestIds.id(4), "t", "p", "o", null, new BigDecimal("100"), 1,
            NOW.minusSeconds(60), NOW.plusSeconds(600), NOW);
    static final DrawEntry ENTRY = new DrawEntry(TestIds.id(5), DRAW, MEMBER, DrawEntryStatus.AWAITING_PAYMENT, null,
            new ShipTo("홍길동", "010-0000-0000", "04524", "서울시", null), NOW);

    final DrawEntryService entryService = mock(DrawEntryService.class);
    final DrawCampaignStore campaigns = mock(DrawCampaignStore.class);
    final DrawEntryStore entries = mock(DrawEntryStore.class);
    final PaymentConfirmer confirmer = mock(PaymentConfirmer.class);
    final PaymentConfirmSlots slots = mock(PaymentConfirmSlots.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final DrawEntryPaymentResults results = new DrawEntryPaymentResults(campaigns, entries, mock(OutboxWriter.class), transactions, clock);
    final DrawEntryPaymentService service = new DrawEntryPaymentService(entryService, campaigns, entries, mock(PaymentPreparer.class), confirmer,
            results, slots, transactions, clock);

    @BeforeEach
    void setUp() {
        given(slots.tryAcquire()).willReturn(true);
        given(entryService.mine(MEMBER, DRAW)).willReturn(ENTRY);
        given(campaigns.findById(DRAW)).willReturn(Optional.of(CAMPAIGN));
        given(entries.findById(any())).willReturn(Optional.of(ENTRY));
        given(confirmer.check(any(), anyString(), anyString(), any())).willReturn(new PaymentConfirmation.Pending());
    }

    /** 승인 중 전환이 경합에 졌는데 다시 읽어 보니 결제 대기면(그 사이 앞선 요청이 끝나 되돌려졌다) 한 번만 판정하고 시작하지 않는다. */
    @Test
    @DisplayName("승인 중 전환에 지고 다시 읽은 응모가 결제 대기면 시작하지 않고 409 — 다시 시작을 되풀이하지 않는다")
    void lostStartRaceIsJudgedOnce() {
        given(entries.requestPayment(any(), anyString(), any())).willReturn(new EntryTransition(false, DrawEntryStatus.AWAITING_PAYMENT));
        given(entries.findById(ENTRY.id())).willReturn(Optional.of(ENTRY));

        assertThatThrownBy(() -> service.confirm(MEMBER, null, DRAW, WINDOW, "key", new BigDecimal("100")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.DRAW_ENTRY_NOT_PAYABLE));
        verify(confirmer, times(1)).check(any(), anyString(), anyString(), any());
        verify(confirmer, never()).confirm(any(), anyString(), anyString(), any(), anyBoolean());
        verify(slots).release();
    }

    @Test
    @DisplayName("승인 중 전환이 잠금 대기 초과면 503 — 시작 전이라 payment 승인을 부르지 않는다")
    void lockTimeoutBeforeStartIsUnavailable() {
        given(entries.requestPayment(any(), anyString(), any())).willThrow(lockWaitTimeout());

        assertThatThrownBy(() -> service.confirm(MEMBER, null, DRAW, WINDOW, "key", new BigDecimal("100")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(confirmer, never()).confirm(any(), anyString(), anyString(), any(), anyBoolean());
        verify(slots).release();
    }

    @Test
    @DisplayName("결과 반영이 잠금 대기 초과면 503 — 날것 500 이 아니다")
    void lockTimeoutWhileSettlingIsUnavailable() {
        given(entries.revert(any(), anyString(), any())).willThrow(lockWaitTimeout());

        assertThatThrownBy(() -> results.notStarted(ENTRY.id(), WINDOW))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    @DisplayName("잠근 상태로 갈래를 고른다 — 승인 중이면 경보 없이, 결제 대기면 결제 완료로 받되 ERROR")
    void approvalForAwaitingEntryIsAlarmed(CapturedOutput output) {
        UUID authorizing = TestIds.id(9);
        given(entries.lockStatus(authorizing)).willReturn(DrawEntryStatus.AUTHORIZING);
        given(entries.approve(authorizing, NOW)).willReturn(new EntryTransition(true, DrawEntryStatus.PAID));

        assertThat(results.approved(authorizing, WINDOW).applied()).isTrue();
        assertThat(output.getAll()).as("승인 중에서 온 승인은 경보가 아니다").doesNotContain("결제 대기 응모에 승인이 왔다");
        verify(entries, never()).approveAwaiting(any(), any());

        given(entries.lockStatus(ENTRY.id())).willReturn(DrawEntryStatus.AWAITING_PAYMENT);
        given(entries.approveAwaiting(ENTRY.id(), NOW)).willReturn(new EntryTransition(true, DrawEntryStatus.PAID));

        assertThat(results.approved(ENTRY.id(), WINDOW).applied()).isTrue();
        assertThat(output.getAll()).contains("ERROR").contains("결제 대기 응모에 승인이 왔다");
        verify(entries, never()).approve(ENTRY.id(), NOW);
    }

    @Test
    @DisplayName("이미 결제 완료면 어느 승인 UPDATE 도 하지 않는다 — 중복")
    void approvalForPaidEntryIsDuplicate() {
        given(entries.lockStatus(ENTRY.id())).willReturn(DrawEntryStatus.PAID);

        assertThat(results.approved(ENTRY.id(), WINDOW).applied()).isFalse();
        verify(entries, never()).approve(any(), any());
        verify(entries, never()).approveAwaiting(any(), any());
    }

    private static CannotAcquireLockException lockWaitTimeout() {
        return new CannotAcquireLockException("lock", new SQLException("lock wait", "40001", 1205));
    }
}
