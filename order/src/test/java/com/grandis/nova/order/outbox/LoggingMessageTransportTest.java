package com.grandis.nova.order.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** 로그 전송기는 보내지 않고 발행 완료로 표시한다. 켜졌다는 사실이 기동 로그에 WARN 으로 남아야 운영 설정 실수가 드러난다. */
@ExtendWith(OutputCaptureExtension.class)
class LoggingMessageTransportTest {

    @Test
    void warnsWhenSelected(CapturedOutput output) {
        new LoggingMessageTransport();

        assertThat(output.getOut()).contains("WARN").contains("nova.outbox.transport=log");
    }
}
