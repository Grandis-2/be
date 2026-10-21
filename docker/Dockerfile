# 서비스가 모두 같이 쓴다. 다른 건 어느 모듈의 jar 를 넣느냐뿐이다.
# jar 는 CI 가 이미 ./gradlew :<module>:bootJar 로 만들어 둔 것을 받는다.
# 여기서 gradle 을 다시 돌리지 않는다 — 서비스마다 반복되면 빌드 시간이 서비스 수만큼 늘어난다.
#
# jar 를 풀어 둔 형태(app.jar + lib/)로 싣는다. fat jar 의 중첩 jar 에서 읽은 클래스는 JVM AOT 캐시에 들어가지 않는다.
# 이 이미지는 캐시가 없는 기반이다. 학습(docker/aot-training/train.sh)이 이 이미지를 띄워 학습한 뒤 그 위에 Dockerfile.aot 로
# 캐시를 얹는다 — 그래서 학습한 JDK 와 실행할 JDK 가 같다. BASE_IMAGE 는 train.sh 가 다이제스트로 고정해 넘겨, 한 번의 학습에서
# 모든 서비스가 같은 베이스를 쓴다.
ARG BASE_IMAGE=eclipse-temurin:25-jre

# OpenTelemetry Java 에이전트 · 그 설정(docker/otel/agent.properties — 쓰지 않는 계측을 끈다) · Pyroscope 프로파일 확장을 싣는다.
# 켜지는 않는다 — 켜는 쪽은 nova-infra 의 작업 정의다(에이전트는 JDK_JAVA_OPTIONS=-javaagent:/otel/javaagent.jar 와 OTEL_*,
# 확장은 profiling 을 켠 서비스에만 OTEL_JAVAAGENT_EXTENSIONS=/otel/pyroscope-otel.jar 와 PYROSCOPE_*).
# JAVA_TOOL_OPTIONS 로 켜지 않는다 — 아래 ENV 의 힙 옵션이 그 변수에 있어, 작업 정의가 덮으면 힙 옵션이 조용히 사라진다.
# 둘을 붙여도 AOT 캐시가 받아지도록 Dockerfile.aot 와 학습(train.sh)이 java.instrument 모듈을 늘 더하고, train.sh 가 둘을 켠 채 검증한다.
# 외부에서 받는 것은 모두 버전과 다이제스트를 같이 고정한다. 이 파일과 설정 파일은 release.yml 의 내용 키에 들어 있어, 바꾸면 서비스
# 이미지가 모두 다시 만들어진다.
FROM ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2.32.0@sha256:9dad1c6e3e2ecee48fc164a19bcfde703510bee972c8cb421689fde89b5f89e9 AS otel

# 확장은 이미지로 나오지 않아 릴리스 파일을 받는다. 릴리스 파일은 다시 올려질 수 있어(immutable 아님) sha256 이 다르면 빌드가 실패한다.
# 같은 릴리스의 pyroscope-otel.jar 는 이름만 같은 작은 라이브러리다 — 확장은 pyroscope-otel-javaagent-extension.jar 이고, 작업 정의가
# 가리키는 이름(pyroscope-otel.jar)으로 둔다. 버전을 올릴 땐 GitHub 릴리스의 digest 로 sha256 을 함께 바꾼다.
# URL 로 받은 파일은 600 이라 --chmod 로 읽게 한다. 빈 scratch 단계에서 받아 /otel 디렉터리 권한은 건드리지 않는다.
FROM scratch AS pyroscope
ARG PYROSCOPE_OTEL_VERSION=2.1.4
ADD --chmod=644 --checksum=sha256:c91cc1b4df86633179eede381ed9df181ba100794632534b5f114661440ef075 \
    https://github.com/grafana/otel-profiling-java/releases/download/v${PYROSCOPE_OTEL_VERSION}/pyroscope-otel-javaagent-extension.jar \
    /pyroscope-otel.jar

FROM ${BASE_IMAGE} AS extract
WORKDIR /build
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /extracted

FROM ${BASE_IMAGE}
WORKDIR /app

# 루트로 돌리지 않는다.
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=extract --chown=spring:spring /extracted/ /app/
COPY --from=otel /javaagent.jar /otel/javaagent.jar
COPY --from=pyroscope /pyroscope-otel.jar /otel/pyroscope-otel.jar
COPY docker/otel/agent.properties /otel/agent.properties
ENV OTEL_JAVAAGENT_CONFIGURATION_FILE=/otel/agent.properties

# 컨테이너에 준 메모리를 기준으로 힙을 잡는다. ECS 태스크 메모리를 바꾸면 따라간다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
