package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.image.ImageAttachments;
import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.integration.Dependencies;
import com.grandis.nova.catalog.integration.DrawGiftClient;
import com.grandis.nova.catalog.integration.InternalCalls;
import com.grandis.nova.catalog.integration.PreorderCampaigns;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.ProductRepository;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 상품 소프트 삭제(2026-10-15 결정). 행은 남기고 삭제 시각과 비공개로 표시한다 — 옵션을 가리키는 주문 · 장바구니 · 예약 · 드로우는 스냅샷 그대로다.
 *
 * <ul>
 *   <li>진행 중 사전예약(오픈 {@link ProductEditService#FREEZE_BEFORE_OPEN} 전부터 마감 전, 회차 취소 전)이면 409 — 판매 중지(회차 취소)가 먼저다.</li>
 *   <li>반영 전 드로우의 증정품이면 409 — order 에 묻는다(드로우는 order 표). 상품 행을 잠근 채 HTTP 를 기다리지 않게 트랜잭션 밖에서 먼저 묻는다.
 *       드로우 생성도 catalog 옵션 조회를 자기 트랜잭션 밖에서 먼저 하고 INSERT 는 뒤에 하므로, 이 확인보다 먼저 옵션을 읽은 생성이 확인 뒤에 커밋되면
 *       막지 못한다 — 둘 다 관리자 작업이다. 삭제가 커밋된 뒤에 옵션 조회를 시작한 드로우 생성은 옵션을 못 찾아 404 다.
 *       반영(settled_at)이 끝나지 않은 채 멈춘 회차가 있으면 그 증정품은 삭제할 수 없다(드로우 취소 경로가 없다).</li>
 *   <li>사진은 전부 떼어 UNUSED 로 둔다 — 지우는 것은 정리 배치(따로 만드는 중)의 몫이고, 드로우 스냅샷이 가리키는 사진은 남기도록 요청해 두었다.</li>
 *   <li>사전예약 상품이면 PREORDER_PRODUCT_CHANGED 를 적는다 — preorder 가 상품 캐시를 비우고(접수가 바로 막힌다) 회차 사본을 비공개로 둔다(오픈 알림 · 대기열).</li>
 * </ul>
 * 삭제한 상품은 수정 · 관리자 상세 · 내부 조회에서 없는 상품(404)이고, 다시 삭제해도 404 다.
 */
@Service
public class ProductDeleteService {

    private final ProductRepository products;
    private final PreorderCampaigns campaigns;
    private final ImageAttachments images;
    private final OutboxWriter outbox;
    private final DrawGiftClient drawGifts;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProductDeleteService(ProductRepository products, PreorderCampaigns campaigns, ImageAttachments images,
                                OutboxWriter outbox, DrawGiftClient drawGifts, PlatformTransactionManager transactionManager, Clock clock) {
        this.products = products;
        this.campaigns = campaigns;
        this.images = images;
        this.outbox = outbox;
        this.drawGifts = drawGifts;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws BusinessException 404 NOT_FOUND — 없거나 이미 삭제한 상품. 409 STATE_CONFLICT — 진행 중 사전예약 · 반영 전 드로우의 증정품.
     *                           503 — order 에 묻지 못했다(다시 시도)
     */
    public void delete(UUID productId) {
        if (products.findById(productId).filter(product -> !product.isDeleted()).isEmpty()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);   // 없는 상품에 order 를 부르지 않는다
        }
        requireNoDrawInProgress(productId);
        transaction.executeWithoutResult(status -> deleteLocked(productId));
    }

    private void deleteLocked(UUID productId) {
        Product product = products.findForUpdate(productId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        requireNoPreorderInProgress(product);
        images.sync(ImagePurpose.PRODUCT, product.getOptions().imageUrls(), List.of(), url -> "images");
        product.delete(clock.instant());
        products.flush();   // UPDATE 를 먼저 내려 제약(CHECK) 위반이 이벤트를 적기 전에 드러나게 한다
        if (product.getSaleMode() == SaleMode.PREORDER) {
            outbox.append(PreorderProductChanged.of(product));
        }
        requireNoPreorderInProgress(product);   // 커밋 직전에 다시 — 잠금 대기 중에 오픈 3분 전이 됐거나 preorder 가 회차를 앞당겼을 수 있다
    }

    private void requireNoPreorderInProgress(Product product) {
        if (product.getSaleMode() != SaleMode.PREORDER || product.getCampaignCanceledAt() != null) {
            return;
        }
        Instant now = clock.instant();
        boolean inProgress = campaigns.find(product.getId())
                .map(window -> !now.isBefore(window.opensAt().minus(ProductEditService.FREEZE_BEFORE_OPEN)) && now.isBefore(window.closesAt()))
                .orElse(false);
        if (inProgress) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT,
                    "사전예약 오픈 3분 전부터 마감 전까지는 삭제할 수 없습니다. 오픈 뒤 판매를 중지(회차 취소)하거나 마감 뒤에 삭제해 주세요.");
        }
    }

    private void requireNoDrawInProgress(UUID productId) {
        DrawGiftClient.DrawGift gift = InternalCalls.call(Dependencies.ORDER, () -> drawGifts.getGift(productId));
        if (!productId.equals(gift.productId()) || gift.drawInProgress() == null) {
            throw InternalCalls.contractViolation(Dependencies.ORDER, "물은 상품과 다르거나 필수 칸이 빈 응답");
        }
        if (gift.drawInProgress()) {
            throw new BusinessException(CatalogErrorCode.STATE_CONFLICT,
                    "진행 중이거나 추첨 · 반영 전인 드로우의 증정품은 삭제할 수 없습니다. 드로우가 끝난 뒤 삭제해 주세요.");
        }
    }
}
