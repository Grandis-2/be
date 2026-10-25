package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.client.payment.PaymentRefundAdmin;
import com.grandis.nova.order.client.payment.PaymentRefundAdminRequest;
import com.grandis.nova.order.client.payment.PaymentRefundAdminResult;
import com.grandis.nova.order.client.payment.PaymentRefundIssue;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 멈춘 환불의 관리자 목록 · 조치. 돈의 사실(결제 · 환불 거래)은 payment 에 있고 주문의 사실(취소 중 · 자동 환불 요청)은 여기 있어 둘을 주문
 * id 로 합친다 — 주문 쪽만으로는 결제 쪽의 확정 실패 · 에스컬레이션이 보이지 않고(자동 환불 주문은 이미 취소라 주문 상태로 안 드러난다),
 * 결제 쪽만으로는 환불 요청이 payment 에 닿지 않은 주문(DLQ 를 버림 등)이 보이지 않는다.
 *
 * 조치는 payment 가 한다(결제사 조회 · 거래 반영). 다시 보내기를 payment 가 받아들이면 주문의 환불 실패 시각을 지우고 이력을 남긴다 — 그래야
 * 다시 실패했을 때 그 실패를 또 적는다. 직접 환불 기록은 payment 가 평소처럼 환불 완료를 알려 주문이 끝난다({@link RefundResults}).
 *
 * 한계: 이 기록은 payment 응답을 받은 뒤 따로 하는 것이라(한 트랜잭션이 아니다) 주문 표시가 돈의 사실과 어긋날 수 있다. 환불 결과
 * (ORDER_REFUND_SETTLED)에 거래 id 가 없어 어느 시도의 결과인지 가를 수 없기 때문이고, 막으려면 그 계약을 바꿔야 한다 — 이번에는 하지 않는다.
 * <ul>
 *   <li>다시 보낸 환불이 이 기록보다 먼저 거절로 확정돼 그 실패가 먼저 닿으면, 옛 실패 시각 때문에 새 실패가 적히지 않고 뒤이은 기록이 그 시각을
 *       지운다 — 화면의 실패 표시가 풀린다.</li>
 *   <li>다시 보내기 응답이 끊기면(503 · 504) 기록하지 않는다 — 실패 표시가 남고 REFUND_RETRIED 가 없으며, 새 거래가 또 실패해도 두 번째 실패는
 *       이력에 없다.</li>
 * </ul>
 * 어느 쪽이든 payment 의 목록(마지막 환불 거래)과 조치 기록이 맞다 — 주문 표시와 payment 목록이 다르면 payment 목록을 따른다.
 */
@Service
public class RefundIssues {

    private static final Logger log = LoggerFactory.getLogger(RefundIssues.class);

    /** 한 번에 읽는 상한. 멈춘 환불은 드물다 — 넘으면 오래된 것부터 상한까지다. */
    public static final int MAX = 500;
    /**
     * 주문 id 로 payment 에 판정을 물을 때 한 번에 싣는 수. GET 쿼리라 주소 길이(Tomcat 요청 줄 · 헤더 기본 8KB)를 넘지 않게 나눈다 — id 하나가
     * "orderIds=…&" 로 약 46바이트라 100개면 약 4.6KB 다.
     */
    static final int LOOKUP_CHUNK = 100;
    static final EventCause REFUND_RETRIED = EventCause.admin("REFUND_RETRIED");

    private final OrderReader orderReader;
    private final OrderLedger ledger;
    private final PaymentRefundAdmin payments;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    public RefundIssues(OrderReader orderReader, OrderLedger ledger, PaymentRefundAdmin payments,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.orderReader = orderReader;
        this.ledger = ledger;
        this.payments = payments;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 주문 쪽 후보와 결제 쪽 후보를 합쳐 오래 멈춘 것부터 상한({@link #MAX})까지. 한쪽을 먼저 채우고 자르면 그쪽 후보가 상한을 채웠을 때 다른
     * 쪽(결제 쪽의 확정 실패 · 에스컬레이션)이 한 건도 보이지 않는다.
     *
     * @param staleHours 진행 중 · 요청한 지 이만큼 지나야 멈춘 것으로 본다
     */
    public List<RefundIssue> list(int staleHours, String sessionToken) {
        return list(staleHours, sessionToken, MAX, LOOKUP_CHUNK);
    }

    /** 상한 · 나눠 묻는 수를 시험에서 낮춘다 — 상한까지 주문을 만들면 공유 DB 의 다른 시험(실행 계획)에 끼어든다. */
    List<RefundIssue> list(int staleHours, String sessionToken, int limit, int lookupChunk) {
        Instant staleBefore = clock.instant().minus(Duration.ofHours(staleHours));
        Map<UUID, Order> orders = new LinkedHashMap<>();
        orderReader.findRefundStuckOrders(staleBefore, limit).forEach(order -> orders.put(order.id(), order));
        Map<UUID, PaymentRefundIssue> byOrder = new LinkedHashMap<>();
        payments.issues(List.of(), staleHours, sessionToken).forEach(issue -> byOrder.put(issue.orderId(), issue));
        List<UUID> unknownToPayment = orders.keySet().stream().filter(id -> !byOrder.containsKey(id)).toList();
        for (int from = 0; from < unknownToPayment.size(); from += lookupChunk) {
            List<UUID> chunk = unknownToPayment.subList(from, Math.min(from + lookupChunk, unknownToPayment.size()));
            payments.issues(chunk, staleHours, sessionToken).forEach(issue -> byOrder.put(issue.orderId(), issue));
        }
        Set<UUID> ids = new LinkedHashSet<>(orders.keySet());
        ids.addAll(byOrder.keySet());
        List<RefundIssue> issues = new ArrayList<>();
        for (UUID id : ids) {
            Optional<Order> order = Optional.ofNullable(orders.get(id)).or(() -> orderReader.findById(id));
            issues.add(RefundIssue.of(order.orElse(null), order.flatMap(o -> orderReader.findAutoRefundRequestedAt(o.id())),
                    byOrder.get(id), id));
        }
        return issues.stream().sorted(Comparator.comparing(RefundIssues::stuckSince).thenComparing(RefundIssue::orderId))
                .limit(limit).toList();
    }

    /**
     * 멈춘 기준 시각 — 주문이 마지막으로 바뀐 시각(취소 중으로 바뀜 · 환불 실패를 적음)과 마지막 환불 거래를 연 시각 중 이른 것. 어느 쪽이든 그
     * 뒤로 일이 진행되지 않았다는 뜻이고, 이른 쪽이 더 오래 멈춘 것이다. 둘 다 없으면(주문도 거래도 모른다) 맨 뒤다.
     */
    private static Instant stuckSince(RefundIssue issue) {
        Instant orderSince = issue.order() == null ? null : issue.order().updatedAt();
        Instant refundSince = issue.payment() == null || issue.payment().refund() == null
                ? null : issue.payment().refund().createdAt();
        if (orderSince == null) {
            return refundSince == null ? Instant.MAX : refundSince;
        }
        return refundSince == null || orderSince.isBefore(refundSince) ? orderSince : refundSince;
    }

    /**
     * payment 가 받아들이면 주문의 환불 실패 시각을 지우고 이력을 남긴다 — 요청하기 전의 시각보다 늦게 적힌 실패(다시 보낸 환불의 새 실패)는
     * 남긴다({@link OrderLedger#noteRefundRetried}).
     */
    public PaymentRefundAdminResult retry(UUID orderId, PaymentRefundAdminRequest request, String sessionToken) {
        Instant requestedAt = clock.instant();
        PaymentRefundAdminResult result = payments.retry(orderId, request, sessionToken);
        try {
            writeTransaction.executeWithoutResult(status -> ledger.noteRefundRetried(orderId, requestedAt, REFUND_RETRIED));
        } catch (RuntimeException e) {
            log.error("관리자 환불 다시 보내기 — payment 는 다시 열었는데 주문에 적지 못했다(주문 표시가 payment 와 다를 수 있다) orderId={}",
                    orderId, e);
        }
        return result;
    }

    public PaymentRefundAdminResult manualComplete(UUID orderId, PaymentRefundAdminRequest request, String sessionToken) {
        return payments.manualComplete(orderId, request, sessionToken);
    }

    /**
     * 한 주문의 목록 한 줄.
     *
     * @param order                 주문이 없으면 null(payment 에만 있는 대상 — 데이터 어긋남)
     * @param autoRefundRequestedAt 결과를 모른 채 취소한 주문의 자동 환불 요청 시각
     * @param payment               payment 의 판정. 어느 쪽에도 없으면 null
     * @param reason                payment 의 판정, 다만 payment 가 이미 환불로 끝냈는데 주문이 아직 모르면 REFUNDED_NOT_APPLIED
     */
    public record RefundIssue(UUID orderId, Order order, Instant autoRefundRequestedAt, PaymentRefundIssue payment,
                              String reason, List<String> actions) {

        static RefundIssue of(Order order, Optional<Instant> autoRefundRequestedAt, PaymentRefundIssue payment, UUID orderId) {
            String reason = payment == null ? "UNKNOWN" : payment.reason();
            if ("REFUNDED".equals(reason) && order != null) {
                reason = "REFUNDED_NOT_APPLIED";
            }
            return new RefundIssue(orderId, order, autoRefundRequestedAt.orElse(null), payment, reason,
                    payment == null ? List.of() : payment.actions());
        }
    }
}
