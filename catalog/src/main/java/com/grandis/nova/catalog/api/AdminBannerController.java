package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.banner.BannerRequest;
import com.grandis.nova.catalog.banner.BannerService;
import com.grandis.nova.catalog.banner.BannerViews;
import com.grandis.nova.catalog.web.StrictBodies;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 관리자 메인 배너 — 목록 · 만들기 · 고치기 · 지우기 · 순서 바꾸기. 게시 기간은 없고, 노출과 순서를 관리자가 정한다. */
@Tag(name = "관리자 배너")
@RestController
@RequestMapping("/api/v1/admin/banners")
public class AdminBannerController {

    private final BannerService banners;
    private final StrictBodies bodies;

    public AdminBannerController(BannerService banners, StrictBodies bodies) {
        this.banners = banners;
        this.bodies = bodies;
    }

    @Operation(summary = "배너 목록", description = "모든 배너, 보이는 순서. targetLive 는 연결 대상이 지금 고객에게 보이는가(일반 판매 상품은 목록에 나오는가, 홍보는 오늘 진행 중인가) — false 면 자동으로 숨는다")
    @GetMapping
    public ApiResponse<Items<BannerViews.Admin>> list() {
        return ApiResponse.ok(new Items<>(banners.list()));
    }

    @Operation(summary = "배너 만들기", description = "맨 뒤에 붙는다. icon 은 lucide 이름 허용 목록만, target 은 일반 판매 상품(PRODUCT) 또는 홍보(PROMOTION), 사진은 업로드 API(purpose=BANNER) 주소만 — 아니면 400. 줄바꿈(\\n)은 그대로 담는다")
    @PostMapping
    public ResponseEntity<ApiResponse<BannerViews.Admin>> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = BannerRequest.class))) @RequestBody String body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(banners.create(bodies.parse(body, BannerRequest.class))));
    }

    @Operation(summary = "배너 고치기", description = "모든 칸을 보낸다. 순서는 바꾸지 않는다(순서 바꾸기 API). 없으면 404")
    @PutMapping("/{bannerId}")
    public ApiResponse<BannerViews.Admin> replace(@PathVariable UUID bannerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = BannerRequest.class))) @RequestBody String body) {
        return ApiResponse.ok(banners.update(bannerId, bodies.parse(body, BannerRequest.class)));
    }

    @Operation(summary = "배너 지우기", description = "204. 되살릴 수 없다. 없으면 404")
    @DeleteMapping("/{bannerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID bannerId) {
        banners.delete(bannerId);
    }

    @Operation(summary = "배너 순서 바꾸기", description = "모든 배너 id 를 보이는 순서대로 한 번씩 보낸다. 빠지거나 남는 id 가 있으면 400(bannerIds) — 목록을 새로 읽고 다시 보낸다")
    @PutMapping("/order")
    public ApiResponse<Items<BannerViews.Admin>> reorder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = OrderRequest.class))) @RequestBody String body) {
        return ApiResponse.ok(new Items<>(banners.reorder(bodies.parse(body, OrderRequest.class).bannerIds())));
    }

    /** @param bannerIds 모든 배너 id, 보이는 순서대로 */
    public record OrderRequest(@NotNull List<@NotNull UUID> bannerIds) {
    }
}
