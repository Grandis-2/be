package com.grandis.nova.catalog.promotion;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 사전예약 홍보. 고객 사전예약 페이지에 사전예약 상품 여러 개를 묶어 보인다. 묶인 상품은 {@link PromotionProducts} 가 따로 다룬다(순서 · 한 상품 한 홍보).
 * 홍보 기간(startDate ~ endDate, 한국 날짜, 끝 날 포함)은 고객 페이지에 보이는 기간이다 — 접수 오픈 · 마감은 상품마다 preorder 회차가 정한다.
 * 이미 있는 홍보를 고치는 경로는 {@link PromotionRepository#findForUpdate} 로 잠그고 읽는다(묶음 교체가 겹치지 않게).
 */
@Entity
@Table(name = "promotions")
public class Promotion extends BaseEntity {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> URLS = new TypeReference<>() {
    };

    /** 관리자 만들기의 Idempotency-Key. */
    @Column(nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 1000)
    private String thumbnailUrl;

    /** 상세 사진 주소 배열(JSON) — 넣은 순서대로 세로로 이어 붙는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String detailImageUrls;

    protected Promotion() {
    }

    private Promotion(String idempotencyKey) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public static Promotion create(String idempotencyKey, String name, LocalDate startDate, LocalDate endDate, String thumbnailUrl,
                                   List<String> detailImageUrls) {
        Promotion promotion = new Promotion(idempotencyKey);
        promotion.change(name, startDate, endDate, thumbnailUrl, detailImageUrls);
        return promotion;
    }

    /** 홍보 내용을 통째로 바꾼다(관리자 수정 폼이 모든 칸을 보낸다). 기간 순서는 서비스가 400 으로 먼저 거른다. */
    public void change(String name, LocalDate startDate, LocalDate endDate, String thumbnailUrl, List<String> detailImageUrls) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate before startDate: " + startDate + " ~ " + endDate);
        }
        this.name = Objects.requireNonNull(name, "name").strip();
        this.startDate = startDate;
        this.endDate = endDate;
        this.thumbnailUrl = Objects.requireNonNull(thumbnailUrl, "thumbnailUrl");
        this.detailImageUrls = JSON.writeValueAsString(List.copyOf(detailImageUrls));
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getName() {
        return name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public List<String> getDetailImageUrls() {
        return List.copyOf(JSON.readValue(detailImageUrls, URLS));
    }

    /** 그 날(한국 날짜) 기준의 진행 단계 — 홍보 기간으로 가른다. */
    public PromotionStatus statusOn(LocalDate today) {
        if (today.isBefore(startDate)) {
            return PromotionStatus.SCHEDULED;
        }
        return today.isAfter(endDate) ? PromotionStatus.ENDED : PromotionStatus.ONGOING;
    }
}
