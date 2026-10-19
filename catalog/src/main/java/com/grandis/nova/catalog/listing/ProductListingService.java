package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.integration.PreorderCampaigns;
import com.grandis.nova.catalog.integration.StockReader;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.OffsetPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 사용자 목록 · 검색. 시각은 앱 시계 하나에서 한 번 읽어 건수와 목록에 같이 쓴다 — 두 번 읽으면 마감 경계에서 둘이 어긋난다.
 * 그래도 count 와 find 는 두 문장이라 READ COMMITTED 에서는 사이에 끼어든 커밋으로 total 과 items 가 어긋난다(실측 total=1 · items=2).
 * 비잠금 SELECT 뿐이라 건수 · 목록 읽기만 REPEATABLE READ 로 올려 한 스냅샷을 쓴다. 트랜잭션이 끝나면 커넥션은 RC 로 돌아간다(실측).
 * order 재고(품절 · 증정품 후보 수량)는 그 트랜잭션이 끝난 뒤 묻는다 — 응답을 기다리는 동안 DB 연결을 붙잡지 않게. preorder 회차는 회원 목록이면
 * 트랜잭션 전에(접수 중이 쿼리 조건이다), 관리자 목록이면 트랜잭션 뒤에 묻는다.
 * 오프셋 페이징은 계약(page · size · total · hasNext)이 요구한다. 상품 목록은 접수 목록처럼 매초 늘어나지 않는다.
 */
@Service
public class ProductListingService {

    private final ProductListingQueryRepository repository;
    private final SoldOutMarker soldOut;
    private final StockReader stocks;
    private final PreorderCampaigns campaigns;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    public ProductListingService(ProductListingQueryRepository repository, SoldOutMarker soldOut, StockReader stocks, PreorderCampaigns campaigns,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.soldOut = soldOut;
        this.stocks = stocks;
        this.campaigns = campaigns;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.clock = clock;
    }

    /**
     * 사전예약은 지금 접수 중인 것만 — preorder 의 접수 중 · 곧 열릴 회차({@link PreorderCampaigns#open})를 이 시각으로 갈라 쿼리에 넣는다. preorder 가
     * 답하지 않으면 마지막 값을 쓰고, 그것도 오래됐으면 사전예약 없이 나간다. 건수 · 목록은 한 스냅샷에서 읽고, 품절은 트랜잭션이 끝난 뒤 order 재고로
     * 채운다({@link SoldOutMarker}).
     */
    public OffsetPage<ProductListItem> list(ProductListFilter filter, ProductSort sort, int page, int size) {
        Instant now = clock.instant();
        // 일반 상품만 거르면 회차가 쓰이지 않는다 — preorder 를 부르지 않아 그 장애에 늦어지지 않는다
        Map<UUID, CampaignWindow> open = filter.saleMode() == SaleMode.IN_STOCK ? Map.of() : campaigns.open();
        Set<UUID> accepting = CampaignWindow.acceptingAt(open, now);
        OffsetPage<ProductListItem> found = snapshot.execute(status -> {
            long total = repository.count(filter, accepting);
            List<ProductListItem> items = total == 0 ? List.of() : repository.find(filter, sort, accepting, page, size);
            return OffsetPage.of(items, page, size, total);
        });
        List<ProductListItem> items = found.items().stream().map(item -> item.withCampaign(open.get(item.productId()), now)).toList();
        return OffsetPage.of(soldOut.mark(items), page, size, found.total());
    }

    /**
     * 럭키 드로우 증정품 후보(관리자 회차 만들기 화면) — 상품마다 후보 옵션과 지금 확보할 수 있는 수량. 건수 · 상품 · 옵션이 한 스냅샷이다.
     *
     * @param q 상품명 · tags · 모델명 · 브랜드 부분 일치. 관리자 목록과 같은 정규화 · 50자 상한. 비면 전부
     */
    public OffsetPage<GiftCandidate> listGiftCandidates(String q, int page, int size) {
        String text = new AdminProductListFilter(q, null, null, List.of()).q();
        OffsetPage<GiftCandidate> found = snapshot.execute(status -> {
            long total = repository.countGiftCandidates(text);
            List<GiftCandidate> items = total == 0 ? List.of() : repository.findGiftCandidates(text, page, size);
            return OffsetPage.of(items, page, size, total);
        });
        // 남은 수량은 order 에 묻는다 — 재고 행이 없는 옵션은 0 으로 둔다(빼면 옵션 없는 후보가 생겨 화면의 기본 선택이 깨진다). 관리자 화면이라
        // order 가 답하지 않으면 503 이다
        Map<UUID, Integer> available = stocks.available(found.items().stream().flatMap(c -> c.options().stream()).map(GiftCandidate.Option::optionId).toList());
        List<GiftCandidate> items = found.items().stream()
                .map(c -> new GiftCandidate(c.productId(), c.title(), c.imageUrl(), c.visible(), c.options().stream()
                        .map(option -> new GiftCandidate.Option(option.optionId(), option.sku(), option.title(), available.getOrDefault(option.optionId(), 0)))
                        .toList()))
                .toList();
        return OffsetPage.of(items, page, size, found.total());
    }

    /**
     * 관리자 목록 — 노출 규칙 없이 전부. 건수와 목록은 위와 같은 이유로 한 스냅샷이다. 사전예약의 회차(시각 · 준비)는 그 쪽의 상품만 트랜잭션 뒤에
     * preorder 에 묻는다 — 관리자 화면이라 preorder 가 답하지 않으면 503 이다.
     */
    public OffsetPage<AdminProductListItem> listForAdmin(AdminProductListFilter filter, int page, int size) {
        OffsetPage<AdminProductListItem> found = snapshot.execute(status -> {
            long total = repository.countForAdmin(filter);
            List<AdminProductListItem> items = total == 0 ? List.of() : repository.findForAdmin(filter, page, size);
            return OffsetPage.of(items, page, size, total);
        });
        Map<UUID, CampaignWindow> windows = campaigns.find(found.items().stream()
                .filter(item -> item.saleMode() == SaleMode.PREORDER).map(AdminProductListItem::productId).toList());
        Instant now = clock.instant();
        List<AdminProductListItem> items = found.items().stream().map(item -> item.withCampaign(windows.get(item.productId()), now)).toList();
        return OffsetPage.of(soldOut.markAdmin(items), page, size, found.total());
    }
}
