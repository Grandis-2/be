package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.cancel.application.PreorderCancelService;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.stereotype.Service;

/**
 * 관리자 예약 취소(모듈 공개 API). 관리자 화면의 다른 기능(정합성 조치)이 부른다.
 * 주문 사전 확인(order 호출)을 하므로 트랜잭션 안에서 부르지 않는다. 결제 완료 예약이면 주문 취소 · 환불까지 이어진다.
 */
@Service
public class AdminCancelService {

    private final PreorderCancelService cancelService;

    AdminCancelService(PreorderCancelService cancelService) {
        this.cancelService = cancelService;
    }

    /**
     * 이미 취소 중 · 취소 완료면 그대로 둔다.
     *
     * @param reason 이력에 남는 사유(필수)
     * @return 취소를 시작한 뒤의 예약 상태
     * @throws BusinessException PREORDER_NOT_FOUND · PREORDER_NOT_CANCELABLE(배송 시작)
     */
    public PreorderStatus cancel(String preorderToken, String reason) {
        return cancelService.cancelByAdmin(preorderToken, reason).status();
    }
}
