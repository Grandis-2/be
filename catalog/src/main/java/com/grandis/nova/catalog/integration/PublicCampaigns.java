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
 * 상세 한 건마다 preorder 를 부르면 preorder 가 느릴 때 상세도 같이 느려진다(2026-10-20 부하 시험: catalog 처리 3ms, preorder 호출 약 2초).
 * 관리자 화면 · 등록 재전송 · 수정 · 삭제 · 일반 전환의 판정은 이것을 쓰지 않고 {@link PreorderCampaigns#find} 로 바로 묻는다 — 낡은 "회차 없음" 으로 판정하지 않게.
 *
 * <ul>
 *   <li><b>기본은 끔</b> — nova.read-cache.preorder-campaign-ttl 이 없으면 늘 preorder 에 묻는다(지금과 같다). 회차가 바뀌었다는 알림을 받는 길이 아직
 *       없어, 켜면 조기 마감 · 다시 열기 · 일정 변경이 TTL 만큼 늦게 보인다. 그 알림을 받아 {@link #CAMPAIGNS} 를 올리는 처리기를 붙인 뒤 길게 켠다.</li>
 *   <li><b>세대는 회차 전체에 하나</b>({@link #CAMPAIGNS}) — 묶음(홍보 카드)을 한 키에 넣으려면 그 묶음의 세대를 조회 전에 하나로 읽어야 한다.
 *       회차가 바뀌는 것은 관리자 조작뿐이라 드물다 — 하나가 바뀌면 전부 다시 묻는다.</li>
 *   <li>접수 단계는 넣지 않는다 — 호출자가 꺼낸 시각으로 계산하므로 오픈 · 마감 시각이 지나는 것은 캐시와 상관없이 바로 보인다.</li>
 *   <li>"회차 없음"(판매 준비 전)도 넣는다. preorder 가 답하지 않으면 넣지 않고 {@link PreorderCampaigns#find} 와 같이 던진다.</li>
 * </ul>
 */
@Component
public class PublicCampaigns {

    /** 회차가 바뀌면 올리는 세대. */
    public static final String CAMPAIGNS = "preorder-campaigns";

    private final PreorderCampaigns campaigns;
    private final ReadCache cache;
    /** null 이면 끔. */
    private final ReadCache.Spec<Lookup> oneSpec;
    private final ReadCache.Spec<Map<UUID, CampaignWindow>> manySpec;

    /** @param ttl 0 이하면 끈다 */
    public PublicCampaigns(PreorderCampaigns campaigns, ReadCache cache, @Value("${nova.read-cache.preorder-campaign-ttl:0s}") Duration ttl) {
        this.campaigns = campaigns;
        this.cache = cache;
        boolean on = ttl.isPositive();
        this.oneSpec = on ? cache.spec("preorder-campaign", Lookup.class, ttl) : null;
        this.manySpec = on ? cache.spec("preorder-campaign-set", new TypeReference<Map<UUID, CampaignWindow>>() { }, ttl) : null;
    }

    /** 한 상품의 회차. 없으면(판매 준비 전) 비어 있다. 실패는 {@link PreorderCampaigns#find(UUID)} 와 같다. */
    public Optional<CampaignWindow> find(UUID productId) {
        if (oneSpec == null) {
            return campaigns.find(productId);
        }
        return Optional.ofNullable(cache.get(oneSpec, CAMPAIGNS, productId.toString(),
                () -> new Lookup(campaigns.find(productId).orElse(null))).window());
    }

    /**
     * 여러 상품의 회차 — 묶음 전체를 한 키로 둔다(상품 수만큼 preorder 를 부르지 않게). 키는 상품 id 집합이라 순서 · 겹침은 같은 키다.
     * 회차가 없는 상품은 빠진다. 빈 목록이면 부르지 않는다. 실패는 {@link PreorderCampaigns#find(Collection)} 와 같다.
     */
    public Map<UUID, CampaignWindow> find(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        if (manySpec == null) {
            return campaigns.find(productIds);
        }
        List<UUID> ids = productIds.stream().distinct().sorted().toList();
        return cache.get(manySpec, CAMPAIGNS, setKey(ids), () -> Map.copyOf(campaigns.find(ids)));
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

    /** 캐시에 넣는 한 상품의 결과 — window 가 null 이면 회차 없음(ReadCache 는 null 을 넣지 않는다). */
    record Lookup(CampaignWindow window) {
    }
}
