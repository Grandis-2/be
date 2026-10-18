package com.grandis.nova.catalog.banner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 배너 만들기 · 고치기 본문 — 모든 칸을 보낸다. 줄바꿈(\n)은 그대로 담는다. 길이는 표의 칸과 같다(글자 수).
 *
 * @param icon      lucide 아이콘 이름 — {@link BannerIcons#ALLOWED} 중 하나
 * @param imageUrl  업로드 API(purpose=BANNER)가 준 사진 주소
 * @param target    연결 대상 — 일반 판매 상품 또는 홍보
 * @param visible   고객에게 보일지(관리자가 끄면 숨김)
 */
public record BannerRequest(
        @NotBlank @Size(max = 20) String badge,
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 200) String description,
        @NotBlank @Size(max = 60) String tabLabel,
        @NotBlank @Size(max = 40) String icon,
        @NotBlank @Size(max = 1000) String imageUrl,
        @NotBlank @Size(max = 100) String imageAlt,
        @NotNull @Valid Target target,
        @NotNull Boolean visible
) {

    public record Target(@NotNull BannerTargetType type, @NotNull UUID id) {
    }
}
