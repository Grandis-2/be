package com.grandis.nova.catalog.banner;

import java.time.Instant;
import java.util.UUID;

/** 배너 응답 모양. */
public final class BannerViews {

    private BannerViews() {
    }

    /** 연결 대상 — 화면이 type 으로 상품 상세 · 홍보 페이지 주소를 만든다. */
    public record Target(BannerTargetType type, UUID id) {
    }

    /** 고객 메인 배너 한 장(GET /api/v1/banners). 순서는 응답 순서다. */
    public record Shown(UUID bannerId, String badge, String title, String description, String tabLabel, String icon, String imageUrl,
                        String imageAlt, Target target) {

        static Shown of(Banner b) {
            return new Shown(b.getId(), b.getBadge(), b.getTitle(), b.getDescription(), b.getTabLabel(), b.getIcon(), b.getImageUrl(),
                    b.getImageAlt(), new Target(b.getTargetType(), b.getTargetId()));
        }
    }

    /**
     * 관리자 배너 한 장. 고객에게 지금 보이는지는 노출(visible)과 대상이 살아 있는지(targetLive) 둘 다다.
     *
     * @param position   보이는 순서(0 부터)
     * @param targetLive 연결 대상이 지금 고객에게 보이는가 — 일반 판매 상품은 목록에 나오는가, 홍보는 오늘 진행 중인가. false 면 자동 숨김
     */
    public record Admin(UUID bannerId, String badge, String title, String description, String tabLabel, String icon, String imageUrl,
                        String imageAlt, Target target, int position, boolean visible, boolean targetLive, Instant createdAt, Instant updatedAt) {

        static Admin of(Banner b, boolean targetLive) {
            return new Admin(b.getId(), b.getBadge(), b.getTitle(), b.getDescription(), b.getTabLabel(), b.getIcon(), b.getImageUrl(),
                    b.getImageAlt(), new Target(b.getTargetType(), b.getTargetId()), b.getPosition(), b.isVisible(), targetLive, b.getCreatedAt(),
                    b.getUpdatedAt());
        }
    }
}
