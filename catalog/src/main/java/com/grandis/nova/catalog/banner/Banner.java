package com.grandis.nova.catalog.banner;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

/**
 * 메인 배너 한 장. 칸은 화면의 배너와 같다(배지 · 제목 · 부가 설명 · 탭 문구 · 탭 아이콘 · 사진 · 사진 대체 문구, 줄바꿈 그대로). 연결 대상이 숨김 ·
 * 판매 중지 · 기간 끝이 돼도 지우지 않는다 — 고객 조회({@link BannerQueryService})에서만 빠진다.
 */
@Entity
@Table(name = "banners")
public class Banner extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String badge;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, length = 60)
    private String tabLabel;

    @Column(nullable = false, length = 40)
    private String icon;

    @Column(nullable = false, length = 1000)
    private String imageUrl;

    @Column(nullable = false, length = 100)
    private String imageAlt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BannerTargetType targetType;

    @Column(nullable = false)
    private UUID targetId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean visible;

    protected Banner() {
    }

    public static Banner create(BannerRequest request, int position) {
        Banner banner = new Banner();
        banner.change(request);
        banner.position = position;
        return banner;
    }

    /** 내용을 통째로 바꾼다(관리자 수정 폼이 모든 칸을 보낸다). 순서는 {@link #moveTo} 로만 바꾼다. */
    public void change(BannerRequest request) {
        this.badge = request.badge();
        this.title = request.title();
        this.description = request.description();
        this.tabLabel = request.tabLabel();
        this.icon = request.icon();
        this.imageUrl = request.imageUrl();
        this.imageAlt = request.imageAlt();
        this.targetType = Objects.requireNonNull(request.target().type(), "targetType");
        this.targetId = Objects.requireNonNull(request.target().id(), "targetId");
        this.visible = request.visible();
    }

    public void moveTo(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0: " + position);
        }
        this.position = position;
    }

    public String getBadge() {
        return badge;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getTabLabel() {
        return tabLabel;
    }

    public String getIcon() {
        return icon;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getImageAlt() {
        return imageAlt;
    }

    public BannerTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public int getPosition() {
        return position;
    }

    public boolean isVisible() {
        return visible;
    }
}
