package com.grandis.nova.payment.client.toss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 토스 결제 승인 · 취소 · 조회. HTTP 결과를 예외 대신 분류된 결과({@link TossCommandResult} · {@link TossLookupResult})로
 * 돌려준다 — 부르는 쪽이 모든 경우를 switch 로 다루게. 트랜잭션 밖에서 부른다(읽기 타임아웃 60초, D9).
 *
 * 분류(D8): 오류는 HTTP 상태가 아니라 토스 코드로 가른다({@link TossErrorCatalog}). 표에 없는 코드 · 타임아웃 · 연결 실패 ·
 * 읽을 수 없는 응답 · 성공 응답의 예상 밖 상태(D7) · 물은 결제와 다른 응답은 모두 결과 불명이다(조회로 확정).
 *
 * 로그: 시크릿 키 · Authorization · 결제 키는 싣지 않는다. RestClient 예외 메시지에는 요청 URL(결제 키가 든 경로)이 들어가므로
 * 예외 자체를 로그에 넘기지 않고 예외 종류만 남긴다. 추적은 토스 orderId 또는 결제 키 끝자리로 한다({@link Call}).
 */
@Component
public class TossPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentClient.class);

    private final TossPaymentsApi api;

    public TossPaymentClient(TossPaymentsApi api) {
        this.api = api;
    }

    /** 결제 승인. 성공은 응답이 DONE 이고 물은 paymentKey · orderId 의 결제일 때뿐이다. */
    public TossCommandResult confirm(TossConfirmRequest request, TossIdempotencyKey idempotencyKey) {
        Call call = Call.ofOrderId("승인", request.orderId());
        return command(call,
                () -> api.confirm(idempotencyKey.value(), request),
                payment -> request.paymentKey().equals(payment.paymentKey()) && request.orderId().equals(payment.orderId()),
                TossPaymentStatus.DONE,
                TossErrorCatalog::confirm);
    }

    /** 결제 전액 취소. 성공은 응답이 CANCELED 이고 물은 paymentKey 의 결제일 때뿐이다. */
    public TossCommandResult cancel(TossCancelRequest request, TossIdempotencyKey idempotencyKey) {
        Call call = Call.ofPaymentKey("취소", request.paymentKey());
        return command(call,
                () -> api.cancel(request.paymentKey(), idempotencyKey.value(), request.body()),
                payment -> request.paymentKey().equals(payment.paymentKey()),
                TossPaymentStatus.CANCELED,
                TossErrorCatalog::cancel);
    }

    public TossLookupResult findByPaymentKey(String paymentKey) {
        TossRequestRules.requirePaymentKey(paymentKey);
        return lookup(Call.ofPaymentKey("조회", paymentKey), () -> api.getByPaymentKey(paymentKey),
                payment -> paymentKey.equals(payment.paymentKey()));
    }

    /**
     * 승인된 결제만 찾는다 — 없음이 곧 실패는 아니다({@link TossLookupResult.NotFound}). 결과 불명 복구에는 쓰지 않는다
     * (처리됐지만 실패한 결제를 못 본다) — {@link #findByPaymentKey} 를 쓴다. 이것은 paymentKey 를 모를 때(성공 응답 유실 등)만 쓴다.
     */
    public TossLookupResult findByOrderId(String orderId) {
        TossRequestRules.requireOrderId(orderId);
        return lookup(Call.ofOrderId("조회", orderId), () -> api.getByOrderId(orderId),
                payment -> orderId.equals(payment.orderId()));
    }

    private TossCommandResult command(Call call, Supplier<ResponseEntity<TossPayment>> send, Predicate<TossPayment> asked,
                                      TossPaymentStatus expected,
                                      Function<String, Optional<TossErrorCatalog.Command>> catalog) {
        ResponseEntity<TossPayment> response;
        try {
            response = send.get();
        } catch (RestClientResponseException e) {
            return commandError(call, e, catalog);
        } catch (RestClientException | HttpMessageConversionException e) {
            return new TossCommandResult.Unknown(transportFailure(call, e), null);
        }
        Optional<UnknownReason> problem = inspect(call, response, asked, expected);
        if (problem.isPresent()) {
            return new TossCommandResult.Unknown(problem.get(), null);
        }
        return new TossCommandResult.Succeeded(response.getBody());
    }

    private TossLookupResult lookup(Call call, Supplier<ResponseEntity<TossPayment>> send, Predicate<TossPayment> asked) {
        ResponseEntity<TossPayment> response;
        try {
            response = send.get();
        } catch (RestClientResponseException e) {
            return lookupError(call, e);
        } catch (RestClientException | HttpMessageConversionException e) {
            return new TossLookupResult.Unknown(transportFailure(call, e), null);
        }
        Optional<UnknownReason> problem = inspect(call, response, asked, null);
        if (problem.isPresent()) {
            return new TossLookupResult.Unknown(problem.get(), null);
        }
        return new TossLookupResult.Found(response.getBody());
    }

    /**
     * 오류가 아닌 응답(2xx · 따라가지 않은 3xx) 검사. 리다이렉트 · 빈 본문 · 다른 결제의 응답 · (승인 · 취소) 기대와 다른 상태면 그 까닭.
     */
    private static Optional<UnknownReason> inspect(Call call, ResponseEntity<TossPayment> response,
                                                   Predicate<TossPayment> asked, TossPaymentStatus expected) {
        if (response.getStatusCode().is3xxRedirection()) {
            // 리다이렉트는 따라가지 않는다(redirects: dont-follow). 토스가 주소를 옮겼거나 앞단이 가로챘다 — 처리 여부는 모른다
            log.warn("토스 {} 결과 불명 — 리다이렉트 응답(따라가지 않음) status={} {}",
                    call.name(), response.getStatusCode(), call.ref());
            return Optional.of(UnknownReason.UNREADABLE_RESPONSE);
        }
        TossPayment payment = response.getBody();
        if (payment == null) {
            // 성공 응답인데 본문이 없다 — 계약 위반이다. 조회도 같은 문제를 겪을 수 있어 사람이 봐야 한다
            log.error("토스 {} 결과 불명 — 빈 2xx 응답 본문 status={} {}", call.name(), response.getStatusCode(), call.ref());
            return Optional.of(UnknownReason.UNREADABLE_RESPONSE);
        }
        if (!asked.test(payment)) {
            // 짝이 어긋난 응답을 성공으로 반영하면 다른 결제의 결과가 이 주문에 붙는다. 연동이 깨진 것이라 사람이 봐야 한다
            log.error("토스 {} 결과 불명 — 물은 결제와 다른 응답 {} returnedOrderId={}",
                    call.name(), call.ref(), payment.orderId());
            return Optional.of(UnknownReason.MISMATCHED_RESPONSE);
        }
        if (expected != null && payment.status() != expected) {
            log.warn("토스 {} 결과 불명 — 예상 밖 상태 status={} {}", call.name(), payment.status(), call.ref());
            return Optional.of(UnknownReason.UNEXPECTED_STATUS);
        }
        return Optional.empty();
    }

    private static TossCommandResult commandError(
            Call call, RestClientResponseException e,
            Function<String, Optional<TossErrorCatalog.Command>> catalog) {
        Optional<TossError> error = readError(call, e);
        if (error.isEmpty()) {
            return new TossCommandResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null);
        }
        String code = error.get().code();
        String message = error.get().message();
        Optional<TossErrorCatalog.Command> verdict = catalog.apply(code);
        if (verdict.isEmpty()) {
            warnUnlisted(call, e, code);
            return new TossCommandResult.Unknown(UnknownReason.UNLISTED_CODE, code);
        }
        logClassified(call, e, code, verdict.get().name());
        return switch (verdict.get()) {
            case REJECTED -> new TossCommandResult.Rejected(code, message);
            case TRANSIENT -> new TossCommandResult.Transient(code, message);
            case PROCESSING -> new TossCommandResult.Processing(code);
            case UNKNOWN -> new TossCommandResult.Unknown(UnknownReason.LISTED_CODE, code);
        };
    }

    private static TossLookupResult lookupError(Call call, RestClientResponseException e) {
        Optional<TossError> error = readError(call, e);
        if (error.isEmpty()) {
            return new TossLookupResult.Unknown(UnknownReason.UNREADABLE_RESPONSE, null);
        }
        String code = error.get().code();
        Optional<TossErrorCatalog.Lookup> verdict = TossErrorCatalog.lookup(code);
        if (verdict.isEmpty()) {
            warnUnlisted(call, e, code);
            return new TossLookupResult.Unknown(UnknownReason.UNLISTED_CODE, code);
        }
        logClassified(call, e, code, verdict.get().name());
        return switch (verdict.get()) {
            case NOT_FOUND -> new TossLookupResult.NotFound(code);
            case UNKNOWN -> new TossLookupResult.Unknown(UnknownReason.LISTED_CODE, code);
        };
    }

    /** 토스 오류 본문 {code, message}. 코드가 없거나 읽을 수 없으면(게이트웨이 오류 페이지 등) 비어 있다. */
    private static Optional<TossError> readError(Call call, RestClientResponseException e) {
        TossError error;
        try {
            error = e.getResponseBodyAs(TossError.class);
        } catch (RuntimeException unreadable) {
            error = null;
        }
        if (error == null || error.code() == null || error.code().isBlank()) {
            log.warn("토스 {} 결과 불명 — 코드 없는 오류 응답 status={} {}", call.name(), e.getStatusCode(), call.ref());
            return Optional.empty();
        }
        return Optional.of(error);
    }

    private static void warnUnlisted(Call call, RestClientResponseException e, String code) {
        log.warn("토스 {} 결과 불명 — 표에 없는 코드 code={} status={} {}",
                call.name(), code, e.getStatusCode(), call.ref());
    }

    private static void logClassified(Call call, RestClientResponseException e, String code, String verdict) {
        if (TossErrorCatalog.isMerchantConfiguration(code)) {
            log.error("토스 {} 상점 설정 오류 — 키 · 계약 확인 필요 code={} status={} 분류={} {}",
                    call.name(), code, e.getStatusCode(), verdict, call.ref());
            return;
        }
        if (verdict.equals(TossErrorCatalog.Command.UNKNOWN.name())) {
            // 결과 불명은 어떤 사유든 WARN 이다 — 복구가 돌 때 같은 수준으로 모아 본다
            log.warn("토스 {} 결과 불명 — 표의 불명 코드 code={} status={} {}",
                    call.name(), code, e.getStatusCode(), call.ref());
            return;
        }
        log.info("토스 {} 오류 응답 code={} status={} 분류={} {}",
                call.name(), code, e.getStatusCode(), verdict, call.ref());
    }

    /**
     * 응답을 받지 못했거나(타임아웃 · 연결 실패 — RestClient 가 ResourceAccessException 으로 올린다), 2xx 응답을 읽지 못했다
     * (본문 변환 실패 · 모르는 Content-Type · 본문 수신 중 기한 초과 — 그 밖의 RestClientException, 그리고 RestClient 가 감싸지
     * 않고 그대로 올리는 HttpMessageConversionException — 예: Jackson 의 타입 정의 오류). 어느 쪽이든 토스가 처리했을 수 있다.
     * 예외 메시지에는 요청 URL(결제 키)이 들어 있어 예외 종류만 남긴다.
     *
     * 기한: Spring 7 의 JDK 요청(JdkClientHttpRequest)은 read-timeout 을 요청 시작부터 본문을 닫을 때까지의 총 기한으로 잰다
     * (TimeoutHandler). 헤더 전에 지나면 HttpTimeoutException(TIMEOUT), 본문을 읽는 중에 지나면 본문 변환 실패(UNREADABLE)다.
     */
    private static UnknownReason transportFailure(Call call, RuntimeException e) {
        Throwable root = rootCause(e);
        if (e instanceof ResourceAccessException) {
            UnknownReason reason = isTimeout(e) ? UnknownReason.TIMEOUT : UnknownReason.CONNECTION_FAILURE;
            log.warn("토스 {} 결과 불명 — {} cause={} {}", call.name(), reason, root.getClass().getName(), call.ref());
            return reason;
        }
        if (hasCause(e, IOException.class)) {
            // 헤더는 받았고 본문을 받는 중에 끊기거나 기한이 지났다 — 네트워크 문제이지 계약 위반이 아니다
            log.warn("토스 {} 결과 불명 — 응답 본문 수신 실패 cause={} {}", call.name(), root.getClass().getName(), call.ref());
        } else {
            // 받은 응답을 해석하지 못했다(본문 모양 · Content-Type · 변환 정의). 계약이 바뀐 것이면 조회도 같은 역직렬화에서
            // 실패해 풀리지 않는다 — 사람이 봐야 한다
            log.error("토스 {} 결과 불명 — 2xx 응답을 읽을 수 없음 cause={} {}", call.name(), root.getClass().getName(), call.ref());
        }
        return UnknownReason.UNREADABLE_RESPONSE;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** JDK 클라이언트 타임아웃(HttpTimeoutException · HttpConnectTimeoutException) · 소켓 타임아웃(SocketTimeoutException 등). */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * 로그 문맥. 승인 · orderId 조회는 토스 orderId(우리가 만든 UUID)로, 취소 · paymentKey 조회는 결제 키 끝 몇 글자로 찾는다 —
     * 결제 키 전체는 싣지 않는다(시크릿 키와 함께면 결제를 조작할 수 있는 식별자). 끝자리로 DB 의 결제 키와 대조해 어느 환불인지 찾는다.
     */
    private record Call(String name, String ref) {

        static Call ofOrderId(String name, String orderId) {
            return new Call(name, "orderId=" + orderId);
        }

        static Call ofPaymentKey(String name, String paymentKey) {
            return new Call(name, "paymentKey=" + TossRequestRules.tail(paymentKey));
        }
    }
}
