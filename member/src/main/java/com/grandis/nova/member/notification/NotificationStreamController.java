package com.grandis.nova.member.notification;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.common.web.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;

/**
 * 안 읽은 수 실시간 연결(SSE). 권한은 다른 알림 API 와 같다(USER, ADMIN 은 403). 인증도 같은 Authorization: Bearer 헤더다 —
 * 화면은 헤더를 실을 수 있는 fetch 기반 SSE 클라이언트를 쓴다.
 *
 * 오류는 이 컨트롤러에서 봉투로 바꾼다. 화면이 Accept: text/event-stream 을 보내 공통 처리기의 JSON 이 협상에 실패하고(실측:
 * HttpMediaTypeNotAcceptableException), 그 뒤 /error 디스패치가 인가에 걸려 403 · 503 이 모두 401 로 나갔다 — 401 이면 화면은 토큰을 다시 받는다.
 * 본문은 공통 처리기가 만들고 여기서는 Content-Type 만 JSON 으로 못 박는다(정해 둔 형식은 협상하지 않는다).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationStreamController {

    private final NotificationStreams streams;
    private final JwtTokenProvider tokens;
    private final GlobalExceptionHandler errors;

    NotificationStreamController(NotificationStreams streams, JwtTokenProvider tokens, GlobalExceptionHandler errors) {
        this.streams = streams;
        this.tokens = tokens;
        this.errors = errors;
    }

    @Operation(summary = "안 읽은 수 실시간 연결", description = "SSE. 연결 뒤 한 주기(2초) 안에, 그리고 새 알림이 생길 때마다 event: unread, data: {\"count\": n}. "
            + "25초마다 주석 한 줄. 액세스 토큰 만료 · 최대 30분에 서버가 닫는다 — 새 토큰으로 다시 연결한다. 503 이면 주기 조회로 돌아간다")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@CurrentCustomerId UUID customerId) {
        SseEmitter emitter = streams.open(customerId, accessTokenExpiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(emitter);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> failed(BusinessException e) {
        return asJson(errors.handleBusiness(e));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> failed(Exception e) {
        return asJson(errors.handleUnexpected(e));
    }

    private static ResponseEntity<ApiResponse<Void>> asJson(ResponseEntity<ApiResponse<Void>> response) {
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }

    /** 이 요청을 인증한 액세스 토큰의 만료. 필터가 검증한 원본을 다시 푼다 — 그 사이 만료됐으면 401 이라 화면이 토큰을 새로 받는다. */
    private Instant accessTokenExpiresAt() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof NovaAuthentication nova)
                || nova.getCredentials() == null) {
            return null;
        }
        try {
            return tokens.parse(nova.getCredentials()).expiresAt();
        } catch (InvalidTokenException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
