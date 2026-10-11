package com.grandis.nova.order.order.query;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.web.Viewer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 예약 취소 가능 여부. preorder 가 사용자 · 관리자 취소를 시작하기 전에 묻는다. 읽기만 한다({@link OrderReader}).
 *
 * 잠그지 않고 읽는 참고값이다. 이 답과 실제 취소 사이에 배송이 시작되면 예약 취소 수신이 REJECTED(SHIPPED) 로 돌려준다.
 */
@Service
@Transactional(readOnly = true)
public class OrderCancelabilityQuery {

    private final OrderReader reader;

    public OrderCancelabilityQuery(OrderReader reader) {
        this.reader = reader;
    }

    /**
     * 주문이 없으면 주인 확인 없이 가능이다 — 확인할 주인이 없고, preorder 가 예약 주인을 이미 확인하고 부른다.
     * 주문이 있으면 관리자이거나 주문 회원이어야 한다. 남의 주문은 403 이고 preorder 가 사용자에게 404 로 숨긴다.
     */
    public Cancelability check(Viewer viewer, UUID preorderId) {
        return reader.findByPreorderId(preorderId)
                .map(order -> {
                    if (!viewer.canSee(order.customerId())) {
                        throw new BusinessException(CommonErrorCode.FORBIDDEN);
                    }
                    return Cancelability.of(order.status());
                })
                .orElse(Cancelability.NO_ORDER);
    }
}
