package com.grandis.nova.preorder.query.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.integration.member.MemberNames;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.application.AdminPreorderFilter;
import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;
import com.grandis.nova.preorder.query.application.PreorderNoteService;
import com.grandis.nova.preorder.query.application.PreorderQueryService;
import com.grandis.nova.preorder.query.application.PreorderView;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 예약 조회. 권한은 보안 설정이 경로로 막는다(ADMIN).
 * 회원 이름(customerName)은 예약을 읽은 뒤 member 에게 한 번에 묻는다({@link MemberNames}) — 받지 못하면 이름만 비우고 응답한다.
 */
@Tag(name = "관리자 · 예약")
@RestController
@RequestMapping("/api/v1/admin/preorders")
class AdminPreorderQueryController {

    private final PreorderQueryService queryService;
    private final PreorderNoteService noteService;
    private final MemberNames memberNames;

    AdminPreorderQueryController(PreorderQueryService queryService, PreorderNoteService noteService, MemberNames memberNames) {
        this.queryService = queryService;
        this.noteService = noteService;
        this.memberNames = memberNames;
    }

    /** 상태 · 화면 단계 · 회원 · 상품 · 옵션 · 배송 차수 · 외부 예약 번호 · 기간 · 등록 작업 상태로 거른다. 전량을 내려주지 않는다. */
    @Operation(summary = "예약 목록 — 상태 · 화면 단계 · 회원 · 상품 · 옵션 · 배송 차수 · 외부 예약 번호 · 기간 · 등록 작업 상태로 거름",
            description = "optionId · batchNumber 는 productId 와 함께 보낸다(없으면 400). status 와 displayStatus 가 맞지 않으면 빈 목록. "
                    + "customerName 은 가린 회원 이름(예: 김**)이고, 회원 정보를 받지 못하면 null")
    @GetMapping
    public ApiResponse<OffsetPage<AdminPreorderSummaryResponse>> list(
            @RequestParam(required = false) PreorderStatus status,
            @RequestParam(required = false) PreorderDisplayStatus displayStatus,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID optionId,
            @RequestParam(required = false) Integer batchNumber,
            @RequestParam(required = false) String externalReference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) SyncJobStatus registerJobStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        // 빈 값은 보내지 않은 것으로 본다 — 화면이 검색 칸을 비운 채 보내도 "번호가 빈 예약"을 찾지 않게
        String reference = externalReference == null || externalReference.isBlank() ? null : externalReference.strip();
        AdminPreorderFilter filter = new AdminPreorderFilter(status, customerId, productId, from, to,
                registerJobStatus, reference, displayStatus, optionId, batchNumber);
        OffsetPage<PreorderView.AdminSummary> found = queryService.findForAdmin(filter, PageSizes.requirePage(page), PageSizes.require(size));
        Map<UUID, String> names = memberNames.find(found.items().stream().map(view -> view.preorder().customerId()).toList());
        return ApiResponse.ok(found.map(view -> AdminPreorderSummaryResponse.from(view, names.get(view.preorder().customerId()))));
    }

    @Operation(summary = "예약 상세 — 동기화 작업 · 시도 · 이력 포함")
    @GetMapping("/{preorderId}")
    public ApiResponse<AdminPreorderDetailResponse> get(@PathVariable String preorderId) {
        return ApiResponse.ok(detail(preorderId));
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
        return ApiResponse.ok(detail(preorderId));
    }

    private AdminPreorderDetailResponse detail(String preorderId) {
        PreorderView.AdminDetail view = queryService.findOneForAdmin(preorderId);
        UUID customerId = view.preorder().customerId();
        return AdminPreorderDetailResponse.from(view, memberNames.find(List.of(customerId)).get(customerId));
    }
}
