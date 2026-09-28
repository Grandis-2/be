package com.grandis.nova.order.order.place;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.preorder.PreorderReader;
import com.grandis.nova.order.client.preorder.PreorderSnapshot;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 주문 생성(order 의 유스케이스). 지금은 사전예약 출처(source=PREORDER)만 받는다 — 다른 출처가 생기면 여기서 나눈다.
 * PAYABLE 예약 하나에 주문 하나(uq_order_preorder)를 만든다. 예약 정보는 preorder 서비스에 HTTP 로 묻는다(client.preorder).
 *
 * 순서: 예약 조회(preorder, 트랜잭션 밖) → 본인 확인 → 기존 주문 확인 → 결제 가능 · 기한 확인 → 원장 place(트랜잭션).
 *
 * <b>요청 경로의 동기 preorder 호출 — 의도한 예외다</b>
 * <ul>
 *   <li>왜 부르나: 주문에 복사할 예약 스냅샷(회원 · 옵션 · 단가 · 상태 · 결제 가능 시각)은 preorder 가 주인이다.
 *       order 는 preorders 를 읽을 수 없고(모듈 경계), 스냅샷을 담아 둘 칸도 없다(이번 에픽은 스키마 변경 금지).
 *       PAYABLE 이벤트로 복제하려면 preorder 의 새 이벤트 · order 의 수신 · 저장 테이블이 모두 필요하다.</li>
 *   <li>부담을 줄이는 장치: 트랜잭션 밖에서만 부른다(DB 잠금을 응답 시간만큼 붙잡지 않는다). 연결 300ms · 읽기 1s
 *       (spring.http.serviceclient.preorder)라 최악에도 요청 하나가 약 1.3초에 끝나고, 응답이 없으면 주문을 만들지 않고
 *       503 이다. 주문 생성은 예약당 한 번(재요청 포함 몇 번)이라 호출량은 결제 시도 수와 같다 — 접수 몰림(10초 5,000건)의 경로가 아니다.</li>
 *   <li>바꿀 조건: preorder 장애가 주문 생성을 막는 것을 받아들일 수 없거나 호출량이 커지면, PAYABLE 이벤트로 스냅샷을
 *       복제해 이 호출을 없앤다(스키마 변경 필요).</li>
 * </ul>
 *
 * - 기존 주문 확인은 재요청을 싸게 끝내고 응답을 시간에 흔들리지 않게 하려는 것이다. "주문 하나" 의 보장은 아니다 —
 *   그건 uq_order_preorder 가 하고, 사이에 끼어든 생성은 {@link OrderAlreadyPlacedException} 으로 같은 결과가 된다.
 * - 트랜잭션은 여기서 직접 연다. 메서드에 @Transactional 을 달면 preorder 호출까지 트랜잭션에 들어간다.
 * - 원장에서 나온 예외는 그 트랜잭션을 rollback-only 로 만든다(원장 계약). 기존 주문 재조회는 새 트랜잭션에서 한다.
 * - 같은 예약으로 동시에 만들다 먼저 들어간 쪽이 롤백되면 기다리던 쪽끼리 교착할 수 있다. 교착은 다시 시도한다 —
 *   다음 시도는 대개 중복 키로 끝나 기존 주문을 돌려준다. 요청 스레드라 시도 사이에 기다리지 않는다.
 *   끝내 교착이면 일시적 경합이므로 503 이다.
 * - 바깥 트랜잭션 안에서 부르면 안 된다. TransactionTemplate(REQUIRED)이 바깥 트랜잭션에 참여해 버려, 원장 예외 뒤의
 *   재시도 · 새 트랜잭션 재조회가 rollback-only 인 같은 트랜잭션에서 일어난다. 그래서 들어올 때 확인한다.
 */
@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    static final int MAX_ATTEMPTS = 3;

    private final PreorderReader preorderReader;
    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    // common:security 도입 시: 그쪽도 Clock 빈을 정의해 타입 주입이 모호해진다. 저장 해상도 시계(JpaAuditingConfig)를
    // 받는지 확인한다(JpaAuditingConfig 주석 참고).
    private final Clock clock;

    public PlaceOrderService(PreorderReader preorderReader, OrderLedger ledger, OrderReader orderReader,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.preorderReader = preorderReader;
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * @param customerId    인증 주체의 회원 id. 요청 본문의 값을 쓰지 않는다
     * @param preorderToken 예약 공개 토큰
     * @throws BusinessException PREORDER_NOT_FOUND(없음 · 남의 예약) · ORDER_ALREADY_CANCELED · PREORDER_NOT_PAYABLE ·
     *                           PAYMENT_WINDOW_EXPIRED · DEPENDENCY_UNAVAILABLE(preorder 응답 없음)
     */
    public PlaceResult place(Long customerId, String preorderToken, PlaceOrderCommand.Address shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("주문 생성은 트랜잭션 밖에서 불러야 한다 — 재시도 · 재조회가 새 트랜잭션이어야 한다");
        }
        PreorderSnapshot preorder = preorderReader.find(preorderToken)
                .filter(snapshot -> snapshot.isOwnedBy(customerId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PREORDER_NOT_FOUND));

        Optional<PlaceResult> existing = findExisting(preorder.id(), customerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!preorder.isPayable()) {
            throw new BusinessException(OrderErrorCode.PREORDER_NOT_PAYABLE);
        }
        if (preorder.isPaymentWindowOver(clock.instant())) {
            throw new BusinessException(OrderErrorCode.PAYMENT_WINDOW_EXPIRED);
        }

        // 입력 검증은 트랜잭션 전에 끝낸다. 원장 안에서 던지면 트랜잭션이 rollback-only 가 된다.
        OrderDraft draft = toCommand(customerId, preorder, shipTo).toDraft();
        EventCause cause = EventCause.user();
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> {
                    Order order = ledger.place(draft, cause);
                    return new PlaceResult(order, orderReader.findItems(order.id()), true);
                });
            } catch (OrderAlreadyPlacedException e) {
                // 커밋된 주문과 부딪혔다. 그 트랜잭션은 이미 롤백됐으므로 새 트랜잭션에서 읽는다.
                return findExisting(preorder.id(), customerId).orElseThrow(() ->
                        new IllegalStateException("중복 키로 거절됐는데 주문이 없다: preorderId=" + preorder.id(), e));
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("주문 생성 교착 {}회, 포기 preorderId={}", MAX_ATTEMPTS, preorder.id(), e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("주문 생성 교착, 다시 시도 {}/{} preorderId={}", attempt, MAX_ATTEMPTS, preorder.id());
            }
        }
    }

    /**
     * 그 예약의 기존 주문. 취소된 주문이면 다시 만들 수 없으므로 409 다(예약당 주문은 평생 하나).
     *
     * 본인 확인은 예약 스냅샷으로 이미 했지만 주문의 회원도 한 번 더 대조한다. 남의 주문(배송지)을 돌려주는 길을
     * 호출 순서 하나에만 맡기지 않는다. 복합 FK(preorder_id, customer_id)상 어긋날 수 없으므로 어긋나면 404 로 숨긴다.
     */
    private Optional<PlaceResult> findExisting(Long preorderId, Long customerId) {
        Optional<PlaceResult> existing = readTransaction.execute(status -> orderReader.findByPreorderId(preorderId)
                .map(order -> new PlaceResult(order, orderReader.findItems(order.id()), false)));
        if (existing.isPresent() && !existing.get().order().customerId().equals(customerId)) {
            throw new BusinessException(OrderErrorCode.PREORDER_NOT_FOUND);
        }
        if (existing.isPresent() && existing.get().order().status() == OrderStatus.CANCELED) {
            throw new BusinessException(OrderErrorCode.ORDER_ALREADY_CANCELED);
        }
        return existing;
    }

    /** 옵션 하나 · 수량 1. 이름 · 단가는 예약 접수 시점 스냅샷을 그대로 옮긴다(카탈로그를 다시 읽지 않는다). */
    private static PlaceOrderCommand toCommand(Long customerId, PreorderSnapshot preorder,
                                               PlaceOrderCommand.Address shipTo) {
        return new PlaceOrderCommand(customerId, OrderSource.PREORDER, preorder.id(), shipTo, List.of(
                new PlaceOrderCommand.Line(preorder.productId(), preorder.optionId(), 1, preorder.unitPrice(),
                        preorder.productTitle(), preorder.optionTitle())));
    }
}
