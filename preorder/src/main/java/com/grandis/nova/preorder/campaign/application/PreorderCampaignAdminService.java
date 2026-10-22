package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.ProductListing;
import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.campaign.domain.OpenAlertStore;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 모집 일정과 배송 차수 관리(관리자). 상품 확인(catalog 호출)은 트랜잭션 밖에서 하고,
 * 바꾸는 일은 {@link PreorderCampaignWriter} 가 회차 행을 잠근 채 한다.
 *
 * 오픈 뒤에는 일정도 차수도 바꾸지 않는다(ERD 결정 29 · 30) — 이미 배정된 순번의 뜻이 달라진다.
 * 마감을 옮기는 조기 마감 · 다시 열기만 예외다 — 오픈 시각 · 차수가 그대로라 배정된 순번의 뜻이 바뀌지 않는다.
 */
@Service
public class PreorderCampaignAdminService {

    private static final Logger log = LoggerFactory.getLogger(PreorderCampaignAdminService.class);

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final PreorderCampaignWriter writer;
    private final CatalogReader catalogReader;
    private final OpenAlertStore openAlerts;

    PreorderCampaignAdminService(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches,
                                        PreorderCampaignWriter writer, CatalogReader catalogReader,
                                        OpenAlertStore openAlerts) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.writer = writer;
        this.catalogReader = catalogReader;
        this.openAlerts = openAlerts;
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
        return upsertCampaign(productId, opensAt, closesAt, null);
    }

    /** @param expectedVisitors 예상 방문자 수. null 이면 그대로 둔다(새 회차는 비어 있다) */
    public PreorderCampaign upsertCampaign(UUID productId, Instant opensAt, Instant closesAt,
                                           Integer expectedVisitors) {
        requirePeriod(opensAt, closesAt);
        ProductListing listing = listingOf(requirePreorderProduct(productId));
        try {
            return writer.upsert(productId, opensAt, closesAt, expectedVisitors, listing);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            return writer.upsert(productId, opensAt, closesAt, expectedVisitors, listing);
        }
    }

    /** 오픈 알림을 신청한 회원 수 — 예상 방문자 수를 정할 때 참고한다. */
    @Transactional(readOnly = true)
    public long countOpenAlertSubscribers(UUID productId) {
        return openAlerts.countSubscribers(productId);
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

    /**
     * 마감된 회차를 새 마감 시각까지 다시 연다. 판매 중지로 마감한 회차(회차 취소)는 열지 않는다 — 회차에는 두 마감을 가르는 칸이
     * 없으므로 catalog 의 지금 상품 상태로 가른다(접수와 같은 기준). 캐시를 비우고 읽어 이벤트를 놓친 옛 값으로 판정하지 않는다.
     *
     * @param reopenedBy 다시 연 관리자. 회차에는 이력이 없어 로그에만 남긴다
     * @throws BusinessException PRODUCT_NOT_FOUND — 회차 · 상품이 없다, SALE_NOT_OPEN — 오픈 전이다,
     *                           CAMPAIGN_NOT_REOPENABLE — 아직 접수 중 · 한 번도 열린 적 없음 · 지금 판매 중인 사전예약 상품이 아님,
     *                           DEPENDENCY_UNAVAILABLE — catalog 가 응답하지 않는다(다시 열었으면 되돌린다)
     */
    public PreorderCampaign reopenCampaign(UUID productId, Instant closesAt, String reopenedBy) {
        if (!liveProduct(productId).orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND))
                .isOnPreorderSale()) {
            throw notOnSale();
        }
        PreorderCampaign reopened = writer.reopen(productId, closesAt, reopenedBy);
        // 확인과 잠금 사이에 판매 중지(회차 취소)가 먼저 끝났으면 그 마감은 다시 연 회차를 보지 못했다. 커밋 뒤 다시 읽어,
        // 판매 중이 아니거나 확인하지 못하면 되돌린다 — 판매 중임을 확인한 회차만 열려 있게
        boolean stillOnSale;
        try {
            stillOnSale = liveProduct(productId).filter(ProductCatalog::isOnPreorderSale).isPresent();
        } catch (RuntimeException e) {
            try {
                writer.closeReopened(productId, reopened.getClosesAt(), reopenedBy);
            } catch (RuntimeException closeFailure) {
                // 되돌리기 실패가 catalog 오류를 덮지 않게 붙여 두고 원래 오류로 답한다
                log.error("다시 연 회차를 되돌리지 못했다 — 열린 채로 남았다 productId={}", productId, closeFailure);
                e.addSuppressed(closeFailure);
                throw e;
            }
            log.warn("다시 연 뒤 상품 판매 상태를 확인하지 못해 되돌렸다 productId={} error={}", productId, e.getMessage());
            throw e;
        }
        if (!stillOnSale) {
            writer.closeReopened(productId, reopened.getClosesAt(), reopenedBy);
            throw notOnSale();
        }
        return reopened;
    }

    @Transactional(readOnly = true)
    public List<ShipmentBatch> findBatches(UUID productId) {
        findCampaign(productId);
        return batches.findByProductIdOrderByBatchNumber(productId);
    }

    public List<ShipmentBatch> replaceBatches(UUID productId, ShipmentBatchPlan plan) {
        return writer.replaceBatches(productId, plan);
    }

    /** catalog 의 지금 상품 값. 캐시를 비우고 읽는다. */
    private Optional<ProductCatalog> liveProduct(UUID productId) {
        catalogReader.evict(productId);
        return catalogReader.findProduct(productId);
    }

    private static BusinessException notOnSale() {
        return new BusinessException(PreorderErrorCode.CAMPAIGN_NOT_REOPENABLE, Map.of("reason", "NOT_ON_SALE"));
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
