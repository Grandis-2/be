package com.grandis.nova.member.notification;

import com.grandis.nova.common.CursorPage;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
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

import java.util.UUID;

/**
 * 내 알림(헤더 종 아이콘). 권한 USER — ADMIN 토큰은 @CurrentCustomerId 리졸버가 403 으로 막는다. 경로는 ALB 에서 member 로 온다
 * (member 가 다른 서비스에 없는 /api/v1/* 를 받는다). 읽음 표시 둘은 본문 없이 204 다 — 화면은 이미 가진 항목의 read 를 바꾸고 배지를 다시 센다.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Operation(summary = "내 알림 목록", description = "최신순(createdAt · notificationId 내림차순) 커서 페이징. size 1~50(기본 20). "
            + "nextCursor 가 null 이면 마지막 쪽")
    @GetMapping
    public ApiResponse<CursorPage<NotificationResponse>> list(
            @CurrentCustomerId UUID customerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(notifications.list(customerId, cursor, size));
    }

    @Operation(summary = "안 읽은 알림 수", description = "헤더 배지용. 화면이 주기적으로 부른다 — 인덱스만 읽는다")
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount(@CurrentCustomerId UUID customerId) {
        return ApiResponse.ok(new UnreadCount(notifications.unreadCount(customerId)));
    }

    @Operation(summary = "알림 하나 읽음", description = "이미 읽었으면 그대로(처음 읽은 시각 유지). 남의 알림 · 없는 id 는 404")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "읽음 — 본문 없음")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@CurrentCustomerId UUID customerId, @PathVariable UUID notificationId) {
        notifications.markRead(customerId, notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 알림 모두 읽음", description = "안 읽은 것만 바꾼다. 없으면 아무것도 바꾸지 않고 204")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "모두 읽음 — 본문 없음")
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@CurrentCustomerId UUID customerId) {
        notifications.markAllRead(customerId);
        return ResponseEntity.noContent().build();
    }

    public record UnreadCount(long count) {
    }
}
