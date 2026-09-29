package com.grandis.nova.order.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로그 전송기는 보내지 않고 발행 완료로 표시한다. 명시 허용 없이는 만들어지지 않아야 하고(운영 설정 실수 → 기동 실패),
 * 허용해 켜졌다는 사실은 기동 로그에 WARN 으로 남는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingMessageTransportTest {

    @Test
    void warnsWhenSelected(CapturedOutput output) {
        new LoggingMessageTransport(true);

        assertThat(output.getOut()).contains("WARN").contains("nova.outbox.transport=log");
    }

    @Test
    void refusesToStartWithoutExplicitAllowance() {
        assertThatThrownBy(() -> new LoggingMessageTransport(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LoggingMessageTransport.ALLOWED_PROPERTY);
    }
}
