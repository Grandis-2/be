package com.grandis.nova.preorder.query;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.web.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 관리자 예약 조회. 권한은 보안 설정이 경로로 막는다(ADMIN). */
@Tag(name = "관리자 · 예약")
@RestController
@RequestMapping("/api/v1/admin/preorders")
public class AdminPreorderQueryController {

    private final PreorderQueryService queryService;
    private final PreorderNoteService noteService;

    public AdminPreorderQueryController(PreorderQueryService queryService, PreorderNoteService noteService) {
        this.queryService = queryService;
        this.noteService = noteService;
    }

    /** 상태 · 회원 · 상품 · 기간 · 등록 작업 상태로 거른다. 전량을 내려주지 않는다. */
    @Operation(summary = "예약 목록 — 상태 · 회원 · 상품 · 기간 · 등록 작업 상태로 거름")
    @GetMapping
    public ApiResponse<OffsetPage<AdminPreorderSummaryResponse>> list(
            @RequestParam(required = false) PreorderStatus status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) SyncJobStatus registerJobStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        AdminPreorderFilter filter = new AdminPreorderFilter(status, customerId, productId, from, to,
                registerJobStatus);
        return ApiResponse.ok(queryService
                .findForAdmin(filter, PageSizes.requirePage(page), PageSizes.require(size))
                .map(AdminPreorderSummaryResponse::from));
    }

    @Operation(summary = "예약 상세 — 동기화 작업 · 시도 · 이력 포함")
    @GetMapping("/{preorderId}")
    public ApiResponse<AdminPreorderDetailResponse> get(@PathVariable String preorderId) {
        return ApiResponse.ok(AdminPreorderDetailResponse.from(queryService.findOneForAdmin(preorderId)));
    }

    /**
     * 내부 메모만 바꾼다. 다른 필드가 본문에 있으면 409 — 신청 내용 변경은 취소 + 새 예약으로 한다.
     * 본문을 Map 으로 받는 이유는 "보낸 필드" 를 알아야 해서다. record 로 받으면 모르는 필드가 조용히 사라진다.
     */
    @Operation(summary = "내부 메모 변경(다른 필드는 409)")
    @PatchMapping("/{preorderId}")
    public ApiResponse<AdminPreorderDetailResponse> updateNote(@PathVariable String preorderId,
                                                               @RequestBody Map<String, Object> request) {
        noteService.changeInternalNote(preorderId, InternalNotes.require(request));
        return ApiResponse.ok(AdminPreorderDetailResponse.from(queryService.findOneForAdmin(preorderId)));
    }
}
