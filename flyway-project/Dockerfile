# 운영 DB 마이그레이션 이미지. release 가 서비스 배포 전에 ECS 태스크로 한 번 돌린다 (.github/scripts/ecs-migrate.sh).
# 접속 정보(FLYWAY_URL · FLYWAY_USER · FLYWAY_PASSWORD)는 태스크 정의가 넣는다 (nova-infra ecs 스택 migration.tf).
# 베이스는 flyway.sh 와 같은 digest 다 — CI(verify.sh)가 검증한 Flyway 그대로 적용한다. 바꿀 때는 둘을 함께 바꾼다.
FROM redgate/flyway:11.20.0@sha256:c92844730f0cccd8f8c34997dfe6be106034df0a1faa186029ee554847b57be0

WORKDIR /flyway/project
COPY flyway.toml ./
COPY migrations ./migrations

# migrate 는 먼저 검증한다(validateOnMigrate). info 는 적용 뒤 상태를 로그에 남긴다
ENTRYPOINT ["flyway", "-configFiles=/flyway/project/flyway.toml"]
CMD ["migrate", "info"]
