package com.grandis.nova.order.draw.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.redis.cache.ReadCache;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.draw.DrawEntryPaymentService;
import com.grandis.nova.order.draw.DrawEntryService;
import com.grandis.nova.order.draw.DrawQueryService;
import com.grandis.nova.order.draw.DrawReadCache;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.order.api.ConfirmPaymentRequest;
import com.grandis.nova.order.web.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 럭키 드로우(회원). 회차 목록 · 상세는 로그인 없이 본다. 응모 · 내 응모 · 응모비 결제는 회원만(관리자는 회원 id 가 없다).
 * 액세스 토큰(Authorization: Bearer)은 payment 에 그대로 전달한다.
 */
@RestController
@RequestMapping(DrawController.BASE_PATH)
@Tag(name = "럭키 드로우")
public class DrawController {

    static final String BASE_PATH = "/api/v1/draws";

    /** 회차 목록 · 상세의 Cache-Control(public) — 앞단 CDN 이 들고 있어도 되는 시간. 새 회차가 이만큼 늦게 보일 수 있다. */
    static final Duration PUBLIC_MAX_AGE = Duration.ofSeconds(10);

    private final DrawQueryService queries;
    private final DrawReadCache cache;
    private final DrawEntryService entries;
    private final DrawEntryPaymentService payments;

    public DrawController(DrawQueryService queries, DrawReadCache cache, DrawEntryService entries, DrawEntryPaymentService payments) {
        this.queries = queries;
        this.cache = cache;
        this.entries = entries;
        this.payments = payments;
    }

    @GetMapping
    @Operation(summary = "회차 목록", description = "최신순. page 는 0 부터, size 1~100. phase(선택): SCHEDULED 예정 · OPEN 응모 중 · CLOSED 마감 — "
            + "주면 그 단계만, 비우면 전부. 응답의 phase 칸도 같은 시각으로 가른다. 모르는 값은 400")
    public ResponseEntity<ApiResponse<OffsetPage<DrawResponse>>> list(@RequestParam(required = false) DrawPhase phase,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        Instant now = queries.now();
        return publicOk(cache.list(phase, now, PageSizes.requirePage(page, checkedSize), checkedSize)
                .map(found -> found.map(c -> DrawResponse.of(c, now))));
    }

    @GetMapping("/{drawId}")
    @Operation(summary = "회차 상세")
    public ResponseEntity<ApiResponse<DrawResponse>> get(@PathVariable UUID drawId) {
        Instant now = queries.now();
        return publicOk(cache.get(drawId).map(c -> DrawResponse.of(c, now)));
    }

    /** max-age 는 상한과, 읽기 캐시 TTL 에서 그 값을 읽은 뒤 남은 시간 중 작은 쪽 — 두 단계를 합쳐도 TTL 을 넘지 않게. */
    private <T> ResponseEntity<ApiResponse<T>> publicOk(ReadCache.Entry<T> entry) {
        long seconds = Math.min(PUBLIC_MAX_AGE.toSeconds(), entry.remaining(DrawReadCache.TTL, queries.now()).toSeconds());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(seconds)).cachePublic()).body(ApiResponse.ok(entry.value()));
    }

    @PostMapping("/{drawId}/entries")
    @Operation(summary = "응모하기", description = "201 새 응모(결제 대기) · 200 이미 응모함(그 응모, 배송지는 바꾸지 않음). 새 응모는 응모 기간에만 — 아니면 409 DRAW_NOT_OPEN")
    public ResponseEntity<ApiResponse<DrawEntryResponse>> enter(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId,
                                                                @Valid @RequestBody EnterDrawRequest request) {
        DrawEntryService.Entered entered = entries.enter(customerId, drawId, request.toShipTo());
        ApiResponse<DrawEntryResponse> body = ApiResponse.ok(DrawEntryResponse.of(entered.entry(), DrawResponse.of(entered.campaign(), queries.now())));
        if (!entered.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + drawId + "/entries/me")).body(body);
    }

    @GetMapping("/entries/me")
    @Operation(summary = "내 응모 목록", description = "응모 최신순. filter: ALL(기본, 전부 — 결제 대기 · 미결제 마감 포함) · WON(당첨만). "
            + "page 는 0 부터, size 1~100. total 은 고른 탭의 건수, counts 는 두 탭의 건수. state: AWAITING_PAYMENT · PAYMENT_CONFIRMING(결제 확인 중 — 마감 뒤에도) · "
            + "AWAITING_RESULT · WON · LOST · CLOSED_UNPAID. 모르는 filter 는 400")
    public ApiResponse<MyDrawEntriesResponse> myEntries(@CurrentCustomerId UUID customerId,
                                                        @RequestParam(defaultValue = "ALL") MyDrawEntryFilter filter,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        DrawEntryService.MyEntries mine = entries.myEntries(customerId, filter == MyDrawEntryFilter.WON,
                PageSizes.requirePage(page, checkedSize), checkedSize);
        return ApiResponse.ok(MyDrawEntriesResponse.of(mine, queries.now()));
    }

    @GetMapping("/{drawId}/entries/me")
    @Operation(summary = "내 응모", description = "그 회차(draw, 회차 상세와 같은 모양)를 같이 준다. 결과(result)는 추첨 반영 뒤 결제 완료(PAID) 응모만 WON · LOST, 반영 전이거나 결제 완료가 아니면 null. 응모하지 않았으면 404 DRAW_ENTRY_NOT_FOUND")
    public ApiResponse<DrawEntryResponse> mine(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId) {
        DrawEntryService.MyEntry mine = entries.view(customerId, drawId);
        return ApiResponse.ok(DrawEntryResponse.of(mine.entry(), mine.result(), DrawResponse.of(mine.campaign(), queries.now())));
    }

    @PostMapping("/{drawId}/entries/me/payment-attempts")
    @Operation(summary = "응모비 결제 준비", description = "201 결제창을 열 값. 금액은 응모비다. 결제 대기 · 응모 기간일 때만")
    public ResponseEntity<ApiResponse<EntryPaymentAttemptResponse>> prepare(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId,
                                                                            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                EntryPaymentAttemptResponse.of(payments.prepare(customerId, BearerTokens.extract(httpRequest).orElse(null), drawId))));
    }

    @PostMapping("/{drawId}/entries/me/payment-attempts/{tossOrderId}/confirm")
    @Operation(summary = "응모비 결제 승인", description = "200 이고 result 로 가른다(APPROVED · DECLINED · PENDING). 금액은 응모비와 대조만 한다")
    public ApiResponse<EntryConfirmResponse> confirm(@CurrentCustomerId UUID customerId, @PathVariable UUID drawId,
                                                     @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String tossOrderId,
                                                     @Valid @RequestBody ConfirmPaymentRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(EntryConfirmResponse.of(payments.confirm(customerId, BearerTokens.extract(httpRequest).orElse(null), drawId,
                tossOrderId, request.paymentKey(), request.amount())));
    }
}
