# 서비스 7개가 같이 쓴다. 다른 건 어느 모듈의 jar 를 넣느냐뿐이다.
# jar 는 CI 가 이미 ./gradlew :<module>:bootJar 로 만들어 둔 것을 받는다.
# 여기서 gradle 을 다시 돌리지 않는다 — 7번 반복되면 빌드 시간이 7배가 된다.
ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jre
WORKDIR /app

# 루트로 돌리지 않는다.
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

ARG JAR_FILE
COPY --chown=spring:spring ${JAR_FILE} app.jar

# 컨테이너에 준 메모리를 기준으로 힙을 잡는다. ECS 태스크 메모리를 바꾸면 따라간다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
