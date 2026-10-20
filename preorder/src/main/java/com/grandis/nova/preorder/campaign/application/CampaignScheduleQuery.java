package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** catalog 가 묻는 회차 일정(내부 API). 공개 여부 · 판매 중지는 거르지 않는다 — catalog 가 원장이다. */
@Service
public class CampaignScheduleQuery {

    static final String TRUNCATED_METRIC = "preorder.internal.campaigns.truncated";
    /** catalog 가 몇 초마다 부르므로 넘칠 때마다 남기면 로그가 넘친다. 지표는 매번 센다. */
    static final Duration TRUNCATED_LOG_INTERVAL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduleQuery.class);

    private final PreorderCampaignRepository campaigns;
    private final CampaignCache cache;
    private final CampaignProperties properties;
    private final Counter truncated;
    private final AtomicReference<Instant> truncatedLoggedAt = new AtomicReference<>();

    CampaignScheduleQuery(PreorderCampaignRepository campaigns, CampaignCache cache, CampaignProperties properties,
                          MeterRegistry meterRegistry) {
        this.campaigns = campaigns;
        this.cache = cache;
        this.properties = properties;
        this.truncated = meterRegistry.counter(TRUNCATED_METRIC);
    }

    /** 마감 전이고 이미 열렸거나 곧(internalLookahead 안에) 열릴 회차. 오픈이 이른 순으로 상한까지. */
    @Transactional(readOnly = true)
    public List<CampaignSchedule> findOpenOrOpeningSoon(Instant now) {
        int limit = properties.internalOpenLimit();
        List<PreorderCampaign> found = campaigns.findOpenBy(now, now.plus(properties.internalLookahead()),
                Limit.of(limit + 1));
        if (found.size() > limit) {
            truncated.increment();
            logTruncated(now, limit);
            found = found.subList(0, limit);
        }
        return found.stream().map(PreorderCampaign::toSchedule).toList();
    }

    /**
     * 그 상품들의 회차. 회차가 없는 상품은 빠진다. 기본은 회차 캐시(바뀌면 커밋 뒤 비운다 — 놓쳤을 때만 캐시 수명만큼 늦다)이고,
     * 판정에 쓰는 호출은 fresh 로 DB 를 읽는다.
     */
    public List<CampaignSchedule> findSchedules(Collection<UUID> productIds, boolean fresh) {
        if (fresh) {
            return campaigns.findAllById(productIds).stream().map(PreorderCampaign::toSchedule).toList();
        }
        return cache.findAll(productIds).stream().map(CachedCampaign::schedule).toList();
    }

    private void logTruncated(Instant now, int limit) {
        Instant last = truncatedLoggedAt.get();
        if ((last == null || !now.isBefore(last.plus(TRUNCATED_LOG_INTERVAL)))
                && truncatedLoggedAt.compareAndSet(last, now)) {
            log.error("접수 중 · 곧 열릴 회차가 응답 상한을 넘어 앞 {}건만 준다 — 상한(nova.campaign.internal-open-limit)을 "
                    + "올리거나 catalog 조회를 나눠야 한다", limit);
        }
    }
}
