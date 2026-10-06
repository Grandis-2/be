package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchPlan;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import com.grandis.nova.preorder.web.ValidationFailures;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 모집 일정과 배송 차수 관리(관리자). 상품 확인(catalog 호출)은 트랜잭션 밖에서 하고,
 * 바꾸는 일은 {@link PreorderCampaignWriter} 가 회차 행을 잠근 채 한다.
 *
 * 오픈 뒤에는 일정도 차수도 바꾸지 않는다(ERD 결정 29 · 30) — 이미 배정된 순번의 뜻이 달라진다.
 */
@Service
public class PreorderCampaignAdminService {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final PreorderCampaignWriter writer;
    private final CatalogReader catalogReader;

    PreorderCampaignAdminService(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                                        PreorderCampaignWriter writer, CatalogReader catalogReader) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.writer = writer;
        this.catalogReader = catalogReader;
    }

    @Transactional(readOnly = true)
    public PreorderCampaign findCampaign(Long productId) {
        return campaigns.findById(productId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * 없으면 만들고 있으면 바꾼다. 회차 행의 주인은 preorder 다 — catalog 는 상품 · 옵션만 만든다.
     *
     * 둘이 동시에 처음 만들면 한쪽이 PK 충돌로 롤백된다. 실패한 트랜잭션은 이어 쓸 수 없으므로
     * 새 트랜잭션에서 한 번 더 한다 — 그때는 행이 있으니 일정 변경으로 끝난다. 다른 제약 위반은 다시 하지 않는다.
     */
    public PreorderCampaign upsertCampaign(Long productId, Instant opensAt, Instant closesAt) {
        requirePeriod(opensAt, closesAt);
        boolean visible = requirePreorderProduct(productId).isVisible();
        try {
            return writer.upsert(productId, opensAt, closesAt, visible);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            return writer.upsert(productId, opensAt, closesAt, visible);
        }
    }

    @Transactional(readOnly = true)
    public List<ShipmentBatch> findBatches(Long productId) {
        findCampaign(productId);
        return batches.findByProductIdOrderByBatchNumber(productId);
    }

    public List<ShipmentBatch> replaceBatches(Long productId, ShipmentBatchPlan plan) {
        return writer.replaceBatches(productId, plan);
    }

    /** 회차 행의 유일 제약은 PK 하나라, 유일 제약 위반이면 동시 생성이다. */
    private boolean isDuplicateKey(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getKind() == ConstraintKind.UNIQUE;
            }
        }
        return false;
    }

    private void requirePeriod(Instant opensAt, Instant closesAt) {
        if (!closesAt.isAfter(opensAt)) {
            throw ValidationFailures.of("closesAt", "opensAt 보다 뒤여야 합니다.");
        }
    }

    /** 사전예약 상품에만 회차가 있다. 일반 상품 · 없는 상품이면 404 다. */
    private ProductCatalog requirePreorderProduct(Long productId) {
        return catalogReader.findProduct(productId)
                .filter(ProductCatalog::isPreorderProduct)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
    }
}
