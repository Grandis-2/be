package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.common.web.client.InternalCallFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * 내부 호출 결과를 이 서비스의 오류로 바꾼다(order 의 PreorderReader 와 같은 규칙).
 * <ul>
 *   <li>404 — 본문의 error.code 가 호출 쪽이 정한 "대상 없음" 코드일 때만 그 오류. 경로 없음 · 주소 오류 · 배포 순서 어긋남의 404 는
 *       공통 NOT_FOUND 라 구분되고, 연동 오류(500)로 남긴다 — 조용히 "없음" 으로 삼키지 않는다.</li>
 *   <li>403 — 연동 오류. catalog 는 늘 회원 토큰을 싣고, 받는 쪽은 남의 대상도 "대상 없음" 404 로 답한다(contracts/order-internal.md) —
 *       403 은 관리자 토큰이 실렸거나 경로 · 권한 설정이 어긋난 것이라 정상 업무 응답이 아니다.</li>
 *   <li>401 — 사용자 토큰 문제라 그대로 401. 그 밖의 4xx · 읽을 수 없는 응답 · 빈 data 는 연동 오류(500).</li>
 *   <li>연결 실패 · 시간 초과 · 5xx 는 일시 장애(503).</li>
 * </ul>
 */
public final class InternalCalls {

    private static final Logger log = LoggerFactory.getLogger(InternalCalls.class);

    private InternalCalls() {
    }

    /** "대상 없음" 이 없는 호출 — 404 도 연동 오류다. */
    public static <T> T call(String dependency, Supplier<ApiResponse<T>> call) {
        return call(dependency, call, null, () -> {
            throw new IllegalStateException("notFound is unreachable without notFoundCode");
        });
    }

    /**
     * @param notFoundCode 받는 쪽의 "대상 없음" 오류 코드
     * @param notFound     그때 던질 이 서비스의 오류
     */
    public static <T> T call(String dependency, Supplier<ApiResponse<T>> call, String notFoundCode,
                             Supplier<BusinessException> notFound) {
        ApiResponse<T> response;
        try {
            response = call.get();
        } catch (HttpClientErrorException.NotFound e) {
            if (notFoundCode != null && notFoundCode.equals(errorCode(e))) {
                throw notFound.get();
            }
            throw integrationError(dependency, "대상 없음이 아닌 404(경로 없음 · 주소 오류)", e);
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        } catch (HttpClientErrorException e) {
            throw integrationError(dependency, e.getStatusCode().toString(), e);
        } catch (RestClientException e) {
            if (InternalCallFailures.isUnreadableResponse(e)) {
                throw integrationError(dependency, "읽을 수 없는 응답", e);
            }
            log.warn("{} 내부 호출 실패 — 일시 장애로 본다", dependency, e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (response == null || response.data() == null) {
            throw integrationError(dependency, "data 없는 응답", null);
        }
        return response.data();
    }

    /** 응답이 계약과 다르다(물은 대상이 아님 · 필수 칸 없음). 다시 물어도 같으므로 연동 오류(500)다. */
    public static BusinessException contractViolation(String dependency, String what) {
        return integrationError(dependency, what, null);
    }

    private static BusinessException integrationError(String dependency, String what, Exception cause) {
        log.error("{} 내부 호출 연동 오류 — {}", dependency, what, cause);
        return new BusinessException(CommonErrorCode.INTERNAL_ERROR);
    }

    private static String errorCode(HttpClientErrorException e) {
        try {
            ApiResponse<?> body = e.getResponseBodyAs(ApiResponse.class);
            return body == null || body.error() == null ? null : body.error().code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
