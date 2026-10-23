package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.draw.domain.exception.DrawEntryTakenException;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawEntryResult;
import com.grandis.nova.order.draw.domain.model.DrawEntryStatus;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.MyDrawEntry;
import com.grandis.nova.order.draw.domain.model.MyDrawEntryCounts;
import com.grandis.nova.order.draw.domain.model.NewDrawEntry;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.order.vo.ShipTo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 회원의 응모. 회차당 하나다 — 이미 응모했으면 그 응모를 돌려준다(배송지를 바꾸지 않는다). 새 응모는 응모 기간(응모 중)에만 받고, 결제 대기로
 * 들어간다. 같은 회원이 동시에 응모하면 유일 키가 하나만 남기고 늦은 쪽은 그 응모를 돌려받는다.
 */
@Service
public class DrawEntryService {

    private final DrawCampaignStore campaigns;
    private final DrawEntryStore entries;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    public DrawEntryService(DrawCampaignStore campaigns, DrawEntryStore entries, PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @throws BusinessException 404 DRAW_NOT_FOUND · 409 DRAW_NOT_OPEN(새 응모인데 응모 기간이 아님)
     */
    public Entered enter(UUID customerId, UUID drawId, ShipTo shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("응모는 트랜잭션 밖에서 불러야 한다 — 유일 키 충돌 뒤 롤백된 트랜잭션 밖에서(트랜잭션 없이) 다시 읽게");
        }
        DrawCampaign campaign = campaigns.lookup(drawId).orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_NOT_FOUND));
        Optional<DrawEntry> existing = find(drawId, customerId);
        if (existing.isPresent()) {
            return new Entered(existing.get(), false, campaign);
        }
        if (campaign.phaseAt(clock.instant()) != DrawPhase.OPEN) {
            throw new BusinessException(OrderErrorCode.DRAW_NOT_OPEN);
        }
        try {
            return new Entered(writeTransaction.execute(status ->
                    entries.insert(new NewDrawEntry(drawId, customerId, shipTo))), true, campaign);
        } catch (DrawEntryTakenException e) {
            // 같은 회원의 응모가 동시에 왔다. 이 트랜잭션은 롤백됐으므로 트랜잭션 없이 먼저 들어간 응모를 읽는다
            return new Entered(find(drawId, customerId)
                    .orElseThrow(() -> new IllegalStateException("응모 충돌인데 그 응모가 없다: drawId=" + drawId, e)), false, campaign);
        }
    }

    /** @throws BusinessException 404 DRAW_ENTRY_NOT_FOUND — 회차가 없거나 응모하지 않았다 */
    public DrawEntry mine(UUID customerId, UUID drawId) {
        return find(drawId, customerId).orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_ENTRY_NOT_FOUND));
    }

    /**
     * 내 응모와 추첨 결과, 그 회차. 결과를 반영하기 전이거나 응모비를 결제하지 않았으면(추첨 대상이 아니다) 결과가 없다(null) — 반영 뒤 결제 완료
     * 응모에 당첨 주문이 이어져 있으면 당첨, 아니면 낙첨이다. 회차는 화면이 다시 조회하지 않게 같이 준다(만든 뒤 바뀌지 않아 읽는 순서와 무관하다).
     * 세 문장을 트랜잭션 없이 차례로 읽는다 — 문장마다 자동 커밋이라 그때 커밋된 값을 본다(격리 수준과 무관). 트랜잭션을 열면 문장마다 준비 · 정리 5개가 더 붙었다.
     *
     * @throws BusinessException 404 DRAW_ENTRY_NOT_FOUND
     */
    public MyEntry view(UUID customerId, UUID drawId) {
        // 반영 표식을 먼저 읽는다 — 반영(당첨 주문 연결과 같은 커밋)이 보였으면 그 뒤에 읽는 응모에도 당첨이 보인다. 반대 순서면 그 사이 반영이
        // 커밋될 때 당첨자에게 낙첨이 보인다
        boolean settled = campaigns.findCloseState(drawId).map(c -> c.settledAt() != null).orElse(false);
        DrawEntry entry = entries.findByCampaignAndCustomer(drawId, customerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_ENTRY_NOT_FOUND));
        DrawEntryResult result = !settled || entry.status() != DrawEntryStatus.PAID ? null
                : entry.wonOrderId() != null ? DrawEntryResult.WON : DrawEntryResult.LOST;
        // 응모가 회차를 외래 키로 가리키고 회차는 지우지 않으므로 응모가 있으면 회차도 있다
        DrawCampaign campaign = campaigns.lookup(drawId)
                .orElseThrow(() -> new IllegalStateException("응모의 회차가 없다: drawId=" + drawId));
        return new MyEntry(entry, result, campaign);
    }

    /**
     * 내 응모 목록 — 응모 최신순. total 은 고른 탭(전체 · 당첨)의 건수다. 응모 · 반영 여부는 한 문장으로 읽는다({@link MyDrawEntry}).
     * 탭 건수는 목록과 다른 문장이라, 그 사이 반영이 커밋되면 한 응답 안에서 건수와 목록이 한순간 어긋날 수 있다(다음 조회에서 맞는다).
     *
     * @param wonOnly 당첨 주문이 이어진 응모만
     */
    public MyEntries myEntries(UUID customerId, boolean wonOnly, int page, int size) {
        MyDrawEntryCounts counts = entries.countByCustomer(customerId);
        List<MyDrawEntry> found = entries.findByCustomerNewestFirst(customerId, wonOnly, page * size, size);
        Map<UUID, DrawCampaign> byId = new HashMap<>();
        campaigns.lookupAll(found.stream().map(MyDrawEntry::campaignId).distinct().toList()).forEach(c -> byId.put(c.id(), c));
        // 응모가 회차를 외래 키로 가리키고 회차는 지우지 않으므로 응모가 있으면 회차도 있다
        found.forEach(entry -> {
            if (!byId.containsKey(entry.campaignId())) {
                throw new IllegalStateException("응모의 회차가 없다: drawId=" + entry.campaignId());
            }
        });
        return new MyEntries(OffsetPage.of(found, page, size, wonOnly ? counts.won() : counts.all()), counts, byId);
    }

    /** 파생 쿼리라 트랜잭션 밖에서 문장 하나다. */
    private Optional<DrawEntry> find(UUID drawId, UUID customerId) {
        return entries.findByCampaignAndCustomer(drawId, customerId);
    }

    /** @param result 결과 반영 전이면 null */
    public record MyEntry(DrawEntry entry, DrawEntryResult result, DrawCampaign campaign) {
    }

    /** @param campaigns 목록에 나온 응모의 회차(회차 id → 회차) */
    public record MyEntries(OffsetPage<MyDrawEntry> entries, MyDrawEntryCounts counts, Map<UUID, DrawCampaign> campaigns) {
    }

    /** @param created 이번 요청이 만들었으면 true, 이미 있던 응모면 false */
    public record Entered(DrawEntry entry, boolean created, DrawCampaign campaign) {
    }
}
