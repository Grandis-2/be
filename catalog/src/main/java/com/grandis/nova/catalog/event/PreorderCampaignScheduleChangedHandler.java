package com.grandis.nova.catalog.event;

import com.grandis.nova.catalog.integration.PreorderCampaigns;
import com.grandis.nova.catalog.integration.PublicCampaigns;
import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * preorder 의 회차 일정 변경(PREORDER_CAMPAIGN_SCHEDULE_CHANGED). preorder 는 그 보내기(nova.campaign.schedule-notice.enabled, 기본 꺼짐)를 켰을 때
 * 일정 번호가 오르는 변경(생성 · 오픈 전 일정 변경 · 조기 마감 · 판매 중지 마감 · 다시 열기 · 다시 열기 되돌림 · 공개 여부)과 전체 재발행마다 보낸다. 공개 조회의 회차 캐시({@link PublicCampaigns}) 세대를 올린다 — 회차마다가
 * 아니라 전체에 하나라 어느 회차가 바뀌어도 전부 다시 묻는다.
 * payload 는 읽지 않는다 — 값은 다음 조회가 preorder 에 다시 묻는다(공개 회차 캐시를 채우는 조회는 preorder 의 회차 캐시를 건너뛰는 fresh 로 묻는다 — {@link PreorderCampaigns#find}). preorder 는
 * 회차를 바꾼 트랜잭션의 아웃박스에 적어 커밋 뒤 보내므로, 받았을 때 그 값은 이미 preorder 에 있다. 그래서 같은 메시지가 두 번 오거나 순서가 뒤집혀도
 * 결과가 같다(세대가 한 번 더 오를 뿐이다).
 * 세대를 못 올리면 던진다 — 메시지를 지우지 않아 다시 받고, 재수신 한도를 넘으면 DLQ 로 간다(그때 늦게 보이는 상한은 공개 회차 캐시의 TTL).
 * 읽기 캐시 전체를 끄면(nova.read-cache.enabled=false) 아무것도 하지 않고 지운다. 공개 회차 캐시만 끈(nova.read-cache.preorder-campaign-ttl 없음)
 * 인스턴스도 세대를 올린다 — 켠 인스턴스와 끈 인스턴스가 섞여 도는 동안 끈 쪽이 메시지를 받아 지우면 켠 쪽의 캐시가 비워지지 않으므로. 그래서 그
 * 설정에서도 Redis 가 실패하면 던지고 결국 DLQ 로 간다.
 */
@Component
class PreorderCampaignScheduleChangedHandler implements CatalogEventHandler {

    static final String EVENT_TYPE = "PREORDER_CAMPAIGN_SCHEDULE_CHANGED";
    static final String AGGREGATE_TYPE = "PREORDER_CAMPAIGN";

    private final ReadCache cache;

    PreorderCampaignScheduleChangedHandler(ReadCache cache) {
        this.cache = cache;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public void handle(UUID productId, EventEnvelope envelope) {
        cache.bumpNow(PublicCampaigns.CAMPAIGNS);
    }
}
