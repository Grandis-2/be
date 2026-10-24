package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 운영 설정은 인프라가 SPRING_APPLICATION_JSON 하나로 넣고, 그 JSON 은 Terraform jsonencode 가 만들어 DB 주소의 & 를 유니코드 이스케이프
 * (역슬래시 + u0026)로 쓴다. Spring 은 클래스패스에 Jackson 이 없으면 간단 파서(BasicJsonParser)로 읽는데, 그 파서는 이스케이프를 풀지 않고
 * 역슬래시만 지워 u0026 을 글자로 남긴다 — DB 주소가 serverTimezone=UTCu0026… 으로 깨져 기동에 실패했다(10-22 ~ 10-24 운영).
 *
 * 시험 클래스패스에는 Flyway 를 통해 Jackson 이 들어와 있어 보통 시험으로는 드러나지 않는다. 그래서 build.gradle 이 넘기는 운영 실행
 * 클래스패스(runtimeClasspath)만으로 클래스로더를 만들고, Spring 이 그 클래스패스에서 고르는 파서로 읽는다.
 * 이스케이프는 소스에 직접 쓰면 컴파일러가 먼저 풀어 버려 문자로 만든다.
 */
class ImageCleanupAppJsonTest {

    @Test
    void 운영_클래스패스로_인프라가_넣는_설정_JSON_을_읽으면_DB_주소의_이스케이프가_풀린다() throws Exception {
        String escapedAmp = (char) 92 + "u0026";
        String json = "{\"spring.datasource.url\":\"jdbc:mysql://db:3306/shop?serverTimezone=UTC" + escapedAmp
                + "sslMode=REQUIRED\"}";

        try (URLClassLoader runtime = new URLClassLoader(runtimeClasspath(), ClassLoader.getPlatformClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            // JsonParserFactory 는 쓸 파서를 스레드 컨텍스트 클래스로더에서 찾는다
            thread.setContextClassLoader(runtime);
            try {
                Object parser = runtime.loadClass("org.springframework.boot.json.JsonParserFactory")
                        .getMethod("getJsonParser").invoke(null);
                Map<?, ?> parsed = (Map<?, ?>) parser.getClass().getMethod("parseMap", String.class).invoke(parser, json);

                assertThat(parsed.get("spring.datasource.url"))
                        .isEqualTo("jdbc:mysql://db:3306/shop?serverTimezone=UTC&sslMode=REQUIRED");
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    private static URL[] runtimeClasspath() {
        String classpath = System.getProperty("nova.runtime-classpath");
        assertThat(classpath).as("build.gradle 의 test 작업이 nova.runtime-classpath 를 넘겨야 한다").isNotBlank();
        return Arrays.stream(classpath.split(File.pathSeparator))
                .map(ImageCleanupAppJsonTest::toUrl)
                .toArray(URL[]::new);
    }

    private static URL toUrl(String path) {
        try {
            return Path.of(path).toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(path, e);
        }
    }
}
