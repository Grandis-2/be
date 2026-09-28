package com.grandis.nova.preorder.payability;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.web.Viewer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * order 가 결제를 시작하기 전에 묻는 결제 가능 여부. 읽기만 하고 잠그지 않는다.
 * 남의 예약이면 403 이다 — 호출한 order 가 사용자에게 404 로 숨긴다.
 */
@Service
class PayabilityService {

    private final PreorderRepository preorders;
    private final Clock clock;

    PayabilityService(PreorderRepository preorders, Clock clock) {
        this.preorders = preorders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Payability check(Viewer viewer, String preorderToken) {
        Preorder preorder = preorders.getByToken(preorderToken);
        if (!viewer.canSee(preorder.getCustomerId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return new Payability(preorder, preorder.payabilityBlocker(clock.instant()));
    }
}
