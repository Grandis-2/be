package com.grandis.nova.order.draw.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.draw.AdminDrawService;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.order.api.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** 관리자 럭키 드로우 회차. 권한은 보안 설정이 경로로 막는다(ADMIN). */
@RestController
@RequestMapping(AdminDrawController.BASE_PATH)
@Tag(name = "관리자 럭키 드로우")
public class AdminDrawController {

    static final String BASE_PATH = "/api/v1/admin/draws";

    private final AdminDrawService service;

    public AdminDrawController(AdminDrawService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "회차 만들기", description = "증정품(일반 판매 · 판매 중 · 재고 등록 옵션, 비공개여도 됨)의 재고를 당첨 인원만큼 확보하고 만든다. 모자라면 409")
    public ResponseEntity<ApiResponse<DrawResponse>> create(@Valid @RequestBody CreateDrawRequest request, HttpServletRequest httpRequest) {
        DrawCampaign created = service.create(BearerTokens.extract(httpRequest).orElse(null), request.toCommand());
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + created.id()))
                .body(ApiResponse.ok(DrawResponse.of(created, service.now())));
    }

    @GetMapping
    @Operation(summary = "회차 목록", description = "최신순. page 는 0 부터, size 1~100")
    public ApiResponse<OffsetPage<DrawResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        OffsetPage<DrawCampaign> found = service.list(PageSizes.requirePage(page, checkedSize), checkedSize);
        Instant now = service.now();
        return ApiResponse.ok(OffsetPage.of(found.items().stream().map(c -> DrawResponse.of(c, now)).toList(), found.page(), found.size(),
                found.total()));
    }

    @GetMapping("/{drawId}")
    @Operation(summary = "회차 상세")
    public ApiResponse<DrawResponse> get(@PathVariable UUID drawId) {
        return ApiResponse.ok(DrawResponse.of(service.get(drawId), service.now()));
    }
}
