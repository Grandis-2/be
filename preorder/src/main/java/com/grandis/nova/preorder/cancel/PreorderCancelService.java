package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.integration.order.OrderCancelabilityChecker;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.Preorders;
import org.springframework.stereotype.Service;

/**
 * 사용자 · 관리자 취소 요청. 사전 확인(order 호출)은 트랜잭션 밖에서 하고, 상태 변경은 {@link CancelStarter} 가 한다.
 *
 * 이미 취소 중 · 취소 완료면 order 에 묻지 않고 지금 상태를 돌려준다(같은 요청을 다시 보내도 결과가 같다).
 * 확인과 취소 시작 사이에 배송이 시작되는 드문 경합은 order 가 REJECTED 로 돌려보내 PAYABLE 로 되돌아간다.
 */
@Service
public class PreorderCancelService {

    private final Preorders preorders;
    private final OrderCancelabilityChecker cancelabilityChecker;
    private final CancelStarter cancelStarter;

    public PreorderCancelService(Preorders preorders, OrderCancelabilityChecker cancelabilityChecker,
                                 CancelStarter cancelStarter) {
        this.preorders = preorders;
        this.cancelabilityChecker = cancelabilityChecker;
        this.cancelStarter = cancelStarter;
    }

    /** 회원 본인의 취소. 남의 예약은 존재를 알리지 않는다(404). */
    public CancelResult cancelByCustomer(Long customerId, String preorderToken, String reason, String sessionToken) {
        PreorderSnapshot preorder = preorders.getByToken(preorderToken);
        if (!preorder.customerId().equals(customerId)) {
            throw new BusinessException(PreorderErrorCode.PREORDER_NOT_FOUND);
        }
        return cancel(preorder, EventActor.USER, reason, CancelReason.USER, sessionToken);
    }

    /** 관리자 취소. 사유는 이력에 남는다(필수). */
    public CancelResult cancelByAdmin(String preorderToken, String reason, String sessionToken) {
        return cancel(preorders.getByToken(preorderToken), EventActor.ADMIN, reason, CancelReason.ADMIN, sessionToken);
    }

    private CancelResult cancel(PreorderSnapshot preorder, EventActor actor, String reason, CancelReason cancelReason,
                                String sessionToken) {
        if (preorder.isCancelable()) {
            cancelabilityChecker.requireCancelable(preorder.preorderToken(), sessionToken);
            cancelStarter.start(preorder, actor, reason, cancelReason);
        }
        PreorderSnapshot current = preorders.getByToken(preorder.preorderToken());
        return new CancelResult(current.preorderToken(), current.status(), current.eventSequence());
    }

}
