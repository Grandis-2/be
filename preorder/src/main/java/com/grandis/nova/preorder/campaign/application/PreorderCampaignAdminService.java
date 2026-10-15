package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 모집 일정과 배송 차수 관리(관리자). 상품 확인(catalog 호출)은 트랜잭션 밖에서 하고,
 * 바꾸는 일은 {@link PreorderCampaignWriter} 가 회차 행을 잠근 채 한다.
 *
 * 오픈 뒤에는 일정도 차수도 바꾸지 않는다(ERD 결정 29 · 30) — 이미 배정된 순번의 뜻이 달라진다.
 * 접수를 지금 멈추는 조기 마감만 예외다 — 마감을 앞당길 뿐이라 배정된 순번은 그대로다.
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
    public PreorderCampaign findCampaign(UUID productId) {
        return campaigns.findById(productId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * 없으면 만들고 있으면 바꾼다. 회차 행의 주인은 preorder 다 — catalog 는 상품 · 옵션만 만든다.
     *
     * 둘이 동시에 처음 만들면 한쪽이 PK 충돌로 롤백된다. 실패한 트랜잭션은 이어 쓸 수 없으므로
     * 새 트랜잭션에서 한 번 더 한다 — 그때는 행이 있으니 일정 변경으로 끝난다. 다른 제약 위반은 다시 하지 않는다.
     */
    public PreorderCampaign upsertCampaign(UUID productId, Instant opensAt, Instant closesAt) {
        requirePeriod(opensAt, closesAt);
        ProductListing listing = listingOf(requirePreorderProduct(productId));
        try {
            return writer.upsert(productId, opensAt, closesAt, listing);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            return writer.upsert(productId, opensAt, closesAt, listing);
        }
    }

    /**
     * 접수 중인 회차를 지금 마감한다(이미 마감이면 그대로). 기존 예약은 건드리지 않는다 — 예약까지 취소하는 것은 회차 취소다.
     *
     * @param closedBy 마감한 관리자. 회차에는 이력이 없어 로그에만 남긴다
     * @throws BusinessException PRODUCT_NOT_FOUND — 회차가 없다, SALE_NOT_OPEN — 아직 오픈 전이다
     */
    public PreorderCampaign closeCampaign(UUID productId, String closedBy) {
        return writer.closeEarly(productId, closedBy);
    }

    @Transactional(readOnly = true)
    public List<ShipmentBatch> findBatches(UUID productId) {
        findCampaign(productId);
        return batches.findByProductIdOrderByBatchNumber(productId);
    }

    public List<ShipmentBatch> replaceBatches(UUID productId, ShipmentBatchPlan plan) {
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

    /**
     * 관리자가 먼저 만드는 회차의 상품 사본. 내부 조회 API 의 값을 번호 0 으로 둔다 — 늦게 온 등록 · 변경 이벤트(1 이상)가 이긴다.
     * 모르는 판매 상태는 비워 둔다(catalog 값을 받기 전과 같다).
     */
    private static ProductListing listingOf(ProductCatalog product) {
        ProductStatus status = Arrays.stream(ProductStatus.values())
                .filter(candidate -> candidate.name().equals(product.status()))
                .findFirst().orElse(null);
        return ProductListing.beforeCatalog(product.title(), status, product.isVisible());
    }

    /** 사전예약 상품에만 회차가 있다. 일반 상품 · 없는 상품이면 404 다. */
    private ProductCatalog requirePreorderProduct(UUID productId) {
        return catalogReader.findProduct(productId)
                .filter(ProductCatalog::isPreorderProduct)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
    }
}
