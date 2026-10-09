# 서비스 8개가 같이 쓴다. 다른 건 어느 모듈의 jar 를 넣느냐뿐이다.
# jar 는 CI 가 이미 ./gradlew :<module>:bootJar 로 만들어 둔 것을 받는다.
# 여기서 gradle 을 다시 돌리지 않는다 — 8번 반복되면 빌드 시간이 8배가 된다.
#
# jar 를 풀어 둔 형태(app.jar + lib/)로 싣는다. fat jar 의 중첩 jar 에서 읽은 클래스는 JVM AOT 캐시에 들어가지 않는다.
# 이 이미지는 캐시가 없는 기반이다. 학습(docker/aot-training/train.sh)이 이 이미지를 띄워 학습한 뒤 그 위에 Dockerfile.aot 로
# 캐시를 얹는다 — 그래서 학습한 JDK 와 실행할 JDK 가 같다. BASE_IMAGE 는 train.sh 가 다이제스트로 고정해 넘겨, 한 번의 학습에서
# 서비스 8개가 같은 베이스를 쓴다.
ARG BASE_IMAGE=eclipse-temurin:25-jre

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

# 컨테이너에 준 메모리를 기준으로 힙을 잡는다. ECS 태스크 메모리를 바꾸면 따라간다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
