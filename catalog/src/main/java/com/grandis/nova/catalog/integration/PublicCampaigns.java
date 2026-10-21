package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.listing.CampaignWindow;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 공개 조회(회원 상품 상세 · 홍보 카드 · 상품 후기 · 조회 신호)가 쓰는 회차 일정 — {@link PreorderCampaigns#find} 의 결과를 읽기 캐시에 둔다.
 * 상세 한 건마다 preorder 를 부르면 preorder 가 느릴 때 상세도 같이 느려진다(2026-10-20 부하 시험의 트레이스: catalog 의 SQL 은 몇 ms, preorder
 * 쪽 회차 조회 처리가 약 2초 — preorder 커넥션 풀이 찼다. catalog 는 읽기 시간 초과 1초에서 포기했다).
 * 관리자 화면 · 등록 재전송 · 수정 · 삭제 · 일반 전환의 판정은 이것을 쓰지 않고 {@link PreorderCampaigns#find} 로 바로 묻는다 — 낡은 "회차 없음" 으로 판정하지 않게.
 *
 * <ul>
 *   <li><b>기본은 끔</b> — nova.read-cache.preorder-campaign-ttl 이 없으면 늘 preorder 에 묻는다. 켜면 회차 변경은 preorder 의
 *       PREORDER_CAMPAIGN_SCHEDULE_CHANGED(catalog-events)를 받아 {@link #CAMPAIGNS} 를 올려 따라간다(PreorderCampaignScheduleChangedHandler).
 *       preorder 의 그 보내기(nova.campaign.schedule-notice.enabled)는 기본 꺼짐이라 그것을 켠 뒤에 켠다 — 안 켠 채 켜면 조기 마감 · 다시 열기 ·
 *       일정 변경이 TTL 만큼 늦게 보인다. 이벤트를 놓쳤을 때(DLQ)도 TTL 이 늦게 보이는 상한이다.
 *       세대는 preorder 의 변경이 회차 API 로 읽히게 된 뒤(그 이벤트를 받았을 때) 올린다 — catalog 의 커밋 때(판매 중지) 올리면 preorder 가 회차를
 *       닫기 전 요청이 옛 회차를 새 세대에 다시 넣는다.</li>
 *   <li><b>세대는 회차 전체에 하나</b>({@link #CAMPAIGNS}) — 묶음(홍보 카드)을 한 키에 넣으려면 그 묶음의 세대를 조회 전에 하나로 읽어야 한다.
 *       preorder 가 그 이벤트를 보내는 때(회차 생성 · 오픈 전 일정 변경 · 조기 마감 · 판매 중지 · 다시 열기 · 공개 여부 · 전체 재발행)마다 전부 다시 묻는다.</li>
 *   <li><b>회차가 있을 때만 넣는다</b> — 등록은 커밋 뒤 preorder 가 회차를 만들기 전까지 "공개인데 회차 없음" 이다. 그때의 "없음" 을 넣으면 회차가
 *       생겨도 TTL 동안 상세가 404 다. 회차 없는 상품(묶음이면 그런 상품이 하나라도 있는 묶음)은 요청마다 preorder 에 묻는다 — 홍보 목록은 그날 보이는 모든 홍보의
 *       상품이 한 묶음이라, 회차 없는 사전예약 상품이 어느 홍보에 하나만 묶여 있어도 목록 전체가 요청마다 묻는다.</li>
 *   <li><b>preorder 에 묻는 방식</b> — 켜면 채울 때 fresh(preorder 의 회차 캐시를 건너뛰고 DB)로 묻는다. 이벤트를 받아 세대를 올린 직후, 아직 자기
 *       캐시를 비우지 않은 preorder 인스턴스가 옛 값을 주면 그 값을 TTL 동안 두게 되기 때문이다. 끄면 fresh 없이 묻는다 — 요청마다 묻는 공개 조회까지
 *       preorder DB 로 보내지 않게(그 값은 이 요청에만 쓴다). 켠 채 읽기 캐시를 쓸 수 없으면(nova.read-cache.enabled=false · Redis 장애로 세대를 못
 *       읽음) 넣지 않으면서도 요청마다 fresh 로 묻는다 — 그동안 공개 조회가 preorder DB 를 읽는다(상품 캐시 등 다른 읽기 캐시도 그동안 DB 로 간다).</li>
 *   <li>접수 단계는 넣지 않는다 — 호출자가 꺼낸 시각으로 계산하므로 오픈 · 마감 시각이 지나는 것은 캐시와 상관없이 바로 보인다.</li>
 *   <li>preorder 가 답하지 않으면 넣지 않고 {@link PreorderCampaigns#find} 와 같이 던진다. 켜 두면 하나 더 — 같은 키를 이 인스턴스에서 먼저 묻고 있는
 *       요청을 기다리는 쪽은 ReadCache 의 기다림 상한(5초)을 넘으면 같은 DEPENDENCY_UNAVAILABLE 을 던진다(그것을 어떻게 답할지는 호출자 몫이다).</li>
 * </ul>
 */
@Component
public class PublicCampaigns {

    /** 회차가 바뀌면 올리는 세대. */
    public static final String CAMPAIGNS = "preorder-campaigns";

    private final PreorderCampaigns campaigns;
    private final ReadCache cache;
    /** null 이면 끔. */
    private final ReadCache.Spec<CampaignWindow> oneSpec;
    private final ReadCache.Spec<Map<UUID, CampaignWindow>> manySpec;

    /** @param ttl 0 이하면 끈다 */
    public PublicCampaigns(PreorderCampaigns campaigns, ReadCache cache, @Value("${nova.read-cache.preorder-campaign-ttl:0s}") Duration ttl) {
        this.campaigns = campaigns;
        this.cache = cache;
        boolean on = ttl.isPositive();
        this.oneSpec = on ? cache.spec("preorder-campaign", CampaignWindow.class, ttl) : null;
        this.manySpec = on ? cache.spec("preorder-campaign-set", new TypeReference<Map<UUID, CampaignWindow>>() { }, ttl) : null;
    }

    /** 한 상품의 회차. 없으면(판매 준비 전) 비어 있다 — 없음은 넣지 않는다. 실패는 위 목록의 마지막 항목. */
    public Optional<CampaignWindow> find(UUID productId) {
        if (oneSpec == null) {
            return campaigns.find(productId, false);
        }
        // loader 가 null(회차 없음)이면 ReadCache 는 넣지 않는다
        return Optional.ofNullable(cache.get(oneSpec, CAMPAIGNS, productId.toString(), () -> campaigns.find(productId, true).orElse(null)));
    }

    /**
     * 여러 상품의 회차 — 묶음 전체를 한 키로 둔다(상품 수만큼 preorder 를 부르지 않게). 키는 상품 id 집합이라 순서 · 겹침은 같은 키다.
     * 회차가 없는 상품은 빠지고, 그런 상품이 있는 묶음은 넣지 않는다. 빈 목록이면 부르지 않는다. 실패는 위 목록의 마지막 항목.
     */
    public Map<UUID, CampaignWindow> find(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        if (manySpec == null) {
            return campaigns.find(productIds, false);
        }
        List<UUID> ids = productIds.stream().distinct().sorted().toList();
        return cache.entry(manySpec, CAMPAIGNS, setKey(ids), () -> Map.copyOf(campaigns.find(ids, true)), found -> found.keySet().containsAll(ids)).value();
    }

    /** 정렬한 id 들의 SHA-256 — 홍보 하나에 상품 20개(전체 목록이면 더)라 그대로 이으면 키가 길다. */
    private static String setKey(List<UUID> sortedIds) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sortedIds.stream().map(UUID::toString).collect(Collectors.joining(",")).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JDK 에 있다", e);
        }
    }
}
