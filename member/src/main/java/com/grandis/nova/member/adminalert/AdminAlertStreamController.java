package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.common.web.GlobalExceptionHandler;
import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

/**
 * 관리자 알림 실시간 연결(SSE). 권한 ADMIN(/api/v1/admin/** 규칙), 인증은 Authorization: Bearer 헤더다(fetch 기반 SSE 클라이언트).
 *
 * 오류의 상태 · 본문은 공통 처리기가 정하고, 여기서는 Content-Type 만 JSON 으로 못 박는다 — 화면이 Accept: text/event-stream 을 보내 공통 처리기의 JSON 이
 * 협상에 실패하고, 그 뒤 /error 디스패치가 401 로 바뀐다(회원 알림의 실시간 연결과 같은 까닭). 그래서 표준 MVC 예외(형식이 틀린 audience 등)는 400,
 * 업무 예외는 그 코드, 나머지만 500 이다. 응답이 이미 시작된 뒤(스트림이 나간 뒤)의 예외는 봉투를 쓰지 않고 WARN 한 줄로 끝낸다 — 던지면 Tomcat 이
 * 이미 나간 응답 위에서 ERROR 를 여러 건(스택 약 100줄) 남긴다.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminAlertStreamController {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertStreamController.class);

    private final AdminAlertStreams streams;
    private final JwtTokenProvider tokens;
    private final GlobalExceptionHandler errors;

    AdminAlertStreamController(AdminAlertStreams streams, JwtTokenProvider tokens, GlobalExceptionHandler errors) {
        this.streams = streams;
        this.tokens = tokens;
        this.errors = errors;
    }

    @Operation(summary = "관리자 알림 실시간 연결", description = "SSE. 연결 뒤 한 주기(2초) 안에 event: unread(unread-count 와 같은 모양), 알림이 생기거나 "
            + "바뀔 때마다(숫자 갱신 · 다시 올림 · 해소 · 읽음) event: notification(목록 항목 하나) 뒤에 unread. audience 를 주면 그 탭의 항목만. "
            + "25초마다 주석 한 줄. 액세스 토큰 만료 · 최대 30분에 서버가 닫는다 — 다시 연결하고 목록을 새로 읽는다. 같은 계정의 연결이 20개를 넘으면 "
            + "가장 오래된 것에 event: replaced 를 보내고 닫는다(다시 연결하지 않는다). 503 이면 주기 조회로 돌아간다")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(required = false) Audience audience) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SseEmitter emitter = streams.open(authentication.getName(), audience, accessTokenExpiresAt(authentication));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(emitter);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> failed(BusinessException e) {
        return asJson(errors.handleBusiness(e));
    }

    /** 표준 MVC 예외는 공통 처리기의 응답(400 등), 그 밖은 500. 응답이 이미 나갔으면 아무것도 쓰지 않는다(null = 처리 끝). */
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failed(Exception e, WebRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("관리자 알림 실시간 연결이 응답을 시작한 뒤 실패해 끝낸다 cause={}: {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
        ResponseEntity<?> handled;
        try {
            handled = errors.handleException(e, request);
        } catch (Exception unhandled) {
            handled = null;
        }
        if (handled == null) {
            handled = errors.handleUnexpected(e);
        }
        return asJson(handled);
    }

    private static <T> ResponseEntity<T> asJson(ResponseEntity<T> response) {
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }

    /** 이 요청을 인증한 액세스 토큰의 만료. 그 사이 만료됐으면 401 이라 화면이 토큰을 새로 받는다. */
    private Instant accessTokenExpiresAt(Authentication authentication) {
        if (!(authentication instanceof NovaAuthentication nova) || nova.getCredentials() == null) {
            return null;
        }
        try {
            return tokens.parse(nova.getCredentials()).expiresAt();
        } catch (InvalidTokenException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
