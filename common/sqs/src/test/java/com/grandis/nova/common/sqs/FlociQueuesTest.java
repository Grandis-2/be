package com.grandis.nova.common.sqs;

import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시험용 Floci 의 큐가 로컬 스크립트(docker/floci/create-queues.sh)와 같은가. 한쪽에만 큐를 더하면 그 목적지로 보내는 서비스의
 * 통합 시험이 없는 큐로 보내다 실패한다. 시험 전용 test-* 큐는 빼고 본다. Floci 를 띄우지 않는다.
 */
class FlociQueuesTest {

    static final Path SCRIPT = Path.of("../../docker/floci/create-queues.sh");
    static final Pattern LOOP = Pattern.compile("^for queue in (.+); do$", Pattern.MULTILINE);

    @Test
    void sameQueuesAsLocalScript() throws IOException {
        Matcher loop = LOOP.matcher(Files.readString(SCRIPT));
        assertThat(loop.find()).as("스크립트의 큐 목록 줄").isTrue();

        assertThat(FlociTestContainer.QUEUES.stream().filter(queue -> !queue.startsWith("test-")))
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(loop.group(1).trim().split("\\s+")));
    }
}
