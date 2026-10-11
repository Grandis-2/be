package com.grandis.nova.catalog.listing;

import com.grandis.nova.common.OffsetPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 사용자 목록 · 검색. 시각은 앱 시계 하나에서 한 번 읽어 건수와 목록에 같이 쓴다 — 두 번 읽으면 마감 경계에서 둘이 어긋난다.
 * 그래도 count 와 find 는 두 문장이라 READ COMMITTED 에서는 사이에 끼어든 커밋으로 total 과 items 가 어긋난다(실측 total=1 · items=2).
 * 비잠금 SELECT 뿐이라 이 메서드만 REPEATABLE READ 로 올려 한 스냅샷을 쓴다. 트랜잭션이 끝나면 커넥션은 RC 로 돌아간다(실측).
 * 오프셋 페이징은 계약(page · size · total · hasNext)이 요구한다. 상품 목록은 접수 목록처럼 매초 늘어나지 않는다.
 */
@Service
public class ProductListingService {

    private final ProductListingQueryRepository repository;
    private final Clock clock;

    public ProductListingService(ProductListingQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OffsetPage<ProductListItem> list(ProductListFilter filter, ProductSort sort, int page, int size) {
        Instant now = clock.instant();
        long total = repository.count(filter, now);
        List<ProductListItem> items = total == 0 ? List.of() : repository.find(filter, sort, now, page, size);
        return OffsetPage.of(items, page, size, total);
    }

    /** 관리자 목록 — 노출 규칙 없이 전부. 건수와 목록은 위와 같은 이유로 한 스냅샷이다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OffsetPage<AdminProductListItem> listForAdmin(AdminProductListFilter filter, int page, int size) {
        Instant now = clock.instant();
        long total = repository.countForAdmin(filter);
        List<AdminProductListItem> items = total == 0 ? List.of() : repository.findForAdmin(filter, now, page, size);
        return OffsetPage.of(items, page, size, total);
    }
}
