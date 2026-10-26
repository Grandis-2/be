package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Status;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 알림창(관리자용 · 개발자용 탭). 권한 ADMIN(/api/v1/admin/** 규칙). 경로는 ALB 에서 member 로 온다. 계약: contracts/admin-alert-hub.md.
 * 배지 · 새 알림은 실시간 연결({@link AdminAlertStreamController})로 받고, 끊긴 동안에만 unread-count 를 주기적으로 부른다.
 * 읽음 표시 둘은 회원 알림 API 와 같게 본문 없이 204 다.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminAlertController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final AdminAlertService alerts;

    AdminAlertController(AdminAlertService alerts) {
        this.alerts = alerts;
    }

    @Operation(summary = "관리자 알림 목록", description = "raisedAt · notificationId 내림차순 커서 페이징. audience · status · severity(쉼표 · 반복) · unreadOnly 로 "
            + "거른다(비우면 거르지 않음). size 1~50(기본 20). nextCursor 가 null 이면 마지막 쪽")
    @GetMapping
    public ApiResponse<CursorPage<AdminAlertResponse>> list(
            @RequestParam(required = false) Audience audience,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) List<Severity> severity,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        // "URGENT," · severity=URGENT&severity= 는 빈 요소가 null 로 바인딩된다 — 거르지 않으면 조회에서 터진다
        if (severity != null && severity.contains(null)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "severity 에 빈 값이 있습니다.");
        }
        return ApiResponse.ok(alerts.list(new AdminAlertQueries.Filter(audience, status, severity, unreadOnly), cursor, size));
    }

    @Operation(summary = "안 읽은 관리자 알림 수", description = "{\"ADMIN\": {urgent, check, info}, \"DEVELOPER\": {…}}. 실시간 연결이 끊긴 동안 화면이 주기적으로 부른다")
    @GetMapping("/unread-count")
    public ApiResponse<Map<Audience, AdminAlertResponse.UnreadCounts>> unreadCount() {
        return ApiResponse.ok(alerts.unreadCounts());
    }

    @Operation(summary = "관리자 알림 하나 읽음", description = "이미 읽었으면 그대로(처음 읽은 시각 유지). 없는 id 는 404")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "읽음 — 본문 없음")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID notificationId) {
        alerts.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "관리자 알림 모두 읽음", description = "audience 를 주면 그 탭만, 비우면 둘 다. 안 읽은 것만 바꾼다")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "모두 읽음 — 본문 없음")
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@RequestParam(required = false) Audience audience) {
        alerts.markAllRead(audience);
        return ResponseEntity.noContent().build();
    }
}
