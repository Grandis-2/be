package com.grandis.nova.payment.domain.model;

import com.grandis.nova.payment.vo.ProviderError;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제사 호출의 결과를 결제 도메인의 말로 옮긴 사건. 결제사 응답 타입은 여기 들어오지 않는다 —
 * 응답 코드를 이 넷 중 하나로 가르는 일은 결제사 클라이언트의 분류를 받은 유스케이스가 한다.
 * CAPTURE 와 REFUND 가 같은 사건을 쓴다(승인됨 / 환불됨 = {@link Confirmed}).
 */
public sealed interface Outcome {

    /** 결제사가 처리를 끝냈다. at 은 결제사가 알린 승인 · 취소 시각. */
    record Confirmed(Instant at) implements Outcome {

        public Confirmed {
            Objects.requireNonNull(at, "at");
        }
    }

    /** 결제사가 거절했고 다시 보내도 결과가 같다. */
    record Rejected(ProviderError error) implements Outcome {

        public Rejected {
            Objects.requireNonNull(error, "error");
        }
    }

    /** 결제사가 아직 처리 중(같은 멱등 키 요청 진행 중)이거나 일시 오류다. retryAfter 뒤에 다시 보낸다. */
    record InProgress(ProviderError error, Duration retryAfter) implements Outcome {

        public InProgress {
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative() || retryAfter.isZero()) {
                throw new IllegalArgumentException("재시도 지연은 0보다 커야 한다: " + retryAfter);
            }
        }
    }

    /** 결제사가 처리했는지 모른다(타임아웃 · 연결 끊김 · 모르는 코드). 실패로 단정하지 않는다. */
    record Unknown(ProviderError error) implements Outcome {

        public Unknown {
            Objects.requireNonNull(error, "error");
        }
    }
}
