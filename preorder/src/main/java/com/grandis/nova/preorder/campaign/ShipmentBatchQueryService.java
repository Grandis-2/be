package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/** 배송 차수 공개 조회. 차수가 없으면 사전예약 상품이 아니거나 아직 준비 전이다. */
@Service
class ShipmentBatchQueryService {

    private final ShipmentBatchRepository batches;

    ShipmentBatchQueryService(ShipmentBatchRepository batches) {
        this.batches = batches;
    }

    List<ShipmentBatch> findPublished(Long productId) {
        List<ShipmentBatch> found = batches.findByProductIdOrderByBatchNumber(productId);
        if (found.isEmpty()) {
            throw new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND);
        }
        return found;
    }
}
