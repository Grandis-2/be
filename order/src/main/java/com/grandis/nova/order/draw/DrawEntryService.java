package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.draw.domain.exception.DrawEntryTakenException;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawEntry;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.NewDrawEntry;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.draw.domain.repository.DrawEntryStore;
import com.grandis.nova.order.order.vo.ShipTo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
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
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public DrawEntryService(DrawCampaignStore campaigns, DrawEntryStore entries, PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * @throws BusinessException 404 DRAW_NOT_FOUND · 409 DRAW_NOT_OPEN(새 응모인데 응모 기간이 아님)
     */
    public Entered enter(UUID customerId, UUID drawId, ShipTo shipTo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("응모는 트랜잭션 밖에서 불러야 한다 — 유일 키 충돌 뒤 새 트랜잭션에서 다시 읽게");
        }
        DrawCampaign campaign = readTransaction.execute(status -> campaigns.findById(drawId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_NOT_FOUND));
        Optional<DrawEntry> existing = find(drawId, customerId);
        if (existing.isPresent()) {
            return new Entered(existing.get(), false);
        }
        if (campaign.phaseAt(clock.instant()) != DrawPhase.OPEN) {
            throw new BusinessException(OrderErrorCode.DRAW_NOT_OPEN);
        }
        try {
            return new Entered(writeTransaction.execute(status ->
                    entries.insert(new NewDrawEntry(drawId, customerId, shipTo))), true);
        } catch (DrawEntryTakenException e) {
            // 같은 회원의 응모가 동시에 왔다. 이 트랜잭션은 롤백됐으므로 새 트랜잭션에서 먼저 들어간 응모를 읽는다
            return new Entered(find(drawId, customerId)
                    .orElseThrow(() -> new IllegalStateException("응모 충돌인데 그 응모가 없다: drawId=" + drawId, e)), false);
        }
    }

    /** @throws BusinessException 404 DRAW_ENTRY_NOT_FOUND — 회차가 없거나 응모하지 않았다 */
    public DrawEntry mine(UUID customerId, UUID drawId) {
        return find(drawId, customerId).orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_ENTRY_NOT_FOUND));
    }

    private Optional<DrawEntry> find(UUID drawId, UUID customerId) {
        return readTransaction.execute(status -> entries.findByCampaignAndCustomer(drawId, customerId));
    }

    /** @param created 이번 요청이 만들었으면 true, 이미 있던 응모면 false */
    public record Entered(DrawEntry entry, boolean created) {
    }
}
