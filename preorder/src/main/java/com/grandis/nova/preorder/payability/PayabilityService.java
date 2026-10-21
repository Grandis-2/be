package com.grandis.nova.preorder.payability;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.web.Viewer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * order 가 결제를 시작하기 전에 묻는 결제 가능 여부. 읽기만 하고 잠그지 않는다.
 * 남의 예약이면 403 이다 — 호출한 order 가 사용자에게 404 로 숨긴다.
 */
@Service
class PayabilityService {

    private final Preorders preorders;
    private final Clock clock;

    PayabilityService(Preorders preorders, Clock clock) {
        this.preorders = preorders;
        this.clock = clock;
    }

    /**
     * 공개 번호로 찾는다.
     *
     * @throws BusinessException PREORDER_NOT_FOUND · FORBIDDEN
     */
    public Payability check(Viewer viewer, String preorderToken) {
        return check(viewer, preorders.getByToken(preorderToken));
    }

    /**
     * 예약 내부 id 로 찾는다(order 가 주문에 저장한 id).
     *
     * @throws BusinessException PREORDER_NOT_FOUND · FORBIDDEN
     */
    public Payability checkById(Viewer viewer, UUID preorderId) {
        return check(viewer, preorders.getById(preorderId));
    }

    private Payability check(Viewer viewer, PreorderSnapshot preorder) {
        if (!viewer.canSee(preorder.customerId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return new Payability(preorder, preorder.payabilityBlocker(clock.instant()));
    }
}
