# shop 스키마와 Flyway

Flyway Community CLI를 Docker로 실행한다. Gradle 모듈이나 상시 실행 서비스는 없다.
MySQL 8.4.11과 Flyway 11.20.0으로 검증하며, 유료 비교/생성 기능은 사용하지 않는다.

## 관리 범위

- `migrations/`: 실제 적용의 정본이자 유일한 shop 정의. 최초 파일은 도입 당시 shop 정의와 설계 주석을 보존한다.
- `flyway.toml`: SQL 경로, shop 스키마, 검증 및 실행 규칙. 접속 비밀값은 넣지 않는다.
- `../docs/external-mock-schema.sql`: 도입 당시 Mock 정의를 보존한 참고 자료. 최신 정의는 Mock 저장소에서 관리한다.

두 스키마가 같은 DB 서버에 있어도 이 프로젝트는 `shop.flyway_schema_history`만 관리한다.
Mock 저장소는 `external_mock`의 마이그레이션과 이력 테이블을 별도로 관리한다.
스키마 설정 자체는 권한 경계가 아니다. 운영의 마이그레이션 계정(`nova_migrator`)은 NCP의 DDL 권한이라, NCP 문서 기준으로
모든 사용자 DB에 ALL PRIVILEGES(WITH GRANT OPTION)를, 전역으로 CREATE USER · REPLICATION SLAVE를 가진다.
그래서 `external_mock`을 포함한 모든 사용자 DB를 바꾸고 계정을 만들어 권한을 넘길 수 있다(권한 축소는 인프라와 따로 정한다).
`external_mock`을 지키는 것은 리뷰와 CI의 external_mock 미생성 검사다.
서비스는 Flyway를 실행하지 않고 기존 Hibernate `ddl-auto: validate`를 유지한다.

## 새 변경 작성

저장소 루트에서 실행한다. Docker와 Python 3.9 이상이 필요하다.

```sh
python3 flyway-project/migrations.py new add_customer_column
```

`Asia/Seoul` 기준 `VyyyyMMddHHmm__설명.sql`을 생성한다. 동일 분의 버전이 있으면
다음 분에 다시 생성한다. 파일 생성 시간 규칙은 DB/JDBC의 UTC 저장 설정과 별개다.
SQL을 작성한 뒤 다음 명령으로 검증한다.

```sh
bash flyway-project/verify.sh
```

이 명령은 전용 임시 MySQL 컨테이너를 생성하고 종료 시 제거한다. 호스트 포트와
영속 볼륨을 사용하지 않으며 기존 로컬 DB나 운영 DB에는 접속하지 않는다.
release가 운영 DB에 돌리는 이미지(`Dockerfile`)도 같은 DB에 돌려, 이미지에 든 마이그레이션 · 설정이 같은지 확인한다.
전체 적용 뒤의 최신 shop 정의는 `build/flyway/schema.sql`로 남는다(저장소에는 두지 않는다. CI에서는 database 잡의
`shop-schema` 산출물). 덤프는 Flyway 이력과 데이터를 제외한다.
설계 설명은 마이그레이션의 SQL 주석에 기록한다. 덤프에는 SQL 주석이 보존되지 않는다.

하나의 변경 목적에 필요한 여러 테이블/FK 변경은 한 마이그레이션에 묶을 수 있다.
최신 테이블 정의를 별도의 schema-model 폴더에 중복 관리하지 않는다.

## 버전 및 CI 규칙

- 기준 브랜치에 합쳐진 마이그레이션은 수정·삭제·이름 변경하지 않는다. 후속 파일을 추가한다.
- 새 파일의 버전은 기준 브랜치의 최신 버전보다 커야 한다. 늦게 합치는 브랜치의 미적용 파일은
  최신 기준 브랜치를 반영한 뒤 새 시각으로 조정하고 SQL 의존 순서를 검토한다.
- 공유 DB에 이미 적용한 파일도 수정하지 않는다. 개인 임시 DB에만 적용한 미병합 파일의
  버전을 바꿨다면 해당 임시 DB를 새로 구성한다.
- CI는 버전 형식·중복·기존 파일 불변성·신규 버전 순서를 검사한다.
- 이어서 빈 MySQL 8.4에 전체 적용, Flyway validate, 재실행 시 이력 불변,
  external_mock 미생성을 확인하고 최신 shop 정의 덤프를 산출물로 올린다.
- CI는 운영 DB의 실제 상태나 업무 의미의 충돌까지 판별하지 않는다.

## 운영 DB(NCP) 준비와 적용

DB 서버, `shop` 데이터베이스, 계정은 nova-infra의 `terraform/ncp/database` 스택이 만든다.
마이그레이션은 `nova_migrator`(NCP DDL 권한) 계정으로, 서비스는 서비스별 CRUD 계정으로 접속한다.
계정 정보는 Secrets Manager의 `nova-prod/db/migrator`, `nova-prod/db/app/<서비스>`에 있다.
DB 자동 생성과 자동 baseline은 비활성화되어 있어 초기 준비 누락이나 기존 테이블을 조용히 건너뛰지 않는다.
Mock DB(`external_mock`)는 Mock 저장소의 절차를 따른다.

DB의 ACG는 AWS NAT 고정 IP만 허용한다. GitHub 러너나 개인 PC에서는 바로 접속할 수 없다.

### release가 적용한다

release.yml의 `migrate` job이 서비스 배포 전에 다음을 한다.

1. 띄울 곳을 확인한다(`.github/scripts/ecs-migrate.sh check`). 태스크 정의, 서브넷, SG는 nova-infra가 SSM 파라미터
   `/nova-prod/migration/run-task`에 적는다. 클러스터가 없으면 경고하고 건너뛰고, 클러스터는 있는데 파라미터가 없으면(인프라 미적용) 실패한다.
2. 이 디렉터리의 `Dockerfile`(Flyway, `flyway.toml`, `migrations/`)로 이미지를 만들어 ECR `nova/migration:latest`로 올린다.
3. ECS 태스크 `nova-prod-migration`을 띄워 `migrate`, `info`를 실행하고 끝날 때까지 기다린다(`ecs-migrate.sh run`).
   태스크가 이 실행이 올린 이미지(digest)로 돌았는지도 확인한다. 용량 공급자는 인프라가 정한다(지금은 Fargate Spot — 회수되면 실패로 끝난다).
4. 태스크가 성공했을 때만 서비스 배포로 넘어간다. Flyway 출력은 워크플로 로그와 CloudWatch `/ecs/nova-prod-migration`에 남는다.

### 직접 적용 (첫 적용, 복구)

AWS 안의 bastion을 거쳐 포트를 연다(인스턴스 ID는 nova-infra bastion 스택의 `bastion_instance_id`,
DB 주소는 ncp/database 스택의 `db_address`). 작업이 끝나면 세션을 바로 닫는다.

```sh
aws ssm start-session --target <bastion 인스턴스 ID> \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["<DB Public 도메인>"],"portNumber":["3306"],"localPortNumber":["13306"]}'
```

다른 셸에서 실행한다. 비밀번호는 화면이나 파일에 남기지 않는다.

```sh
s=$(aws secretsmanager get-secret-value --secret-id nova-prod/db/migrator --query SecretString --output text)
export FLYWAY_USER=$(jq -r .username <<< "$s") FLYWAY_PASSWORD=$(jq -r .password <<< "$s"); unset s
export FLYWAY_URL='jdbc:mysql://host.docker.internal:13306/shop?useSsl=true&trustServerCertificate=true'
bash flyway-project/flyway.sh info
bash flyway-project/flyway.sh migrate
bash flyway-project/flyway.sh validate
```

Flyway 이미지에 든 JDBC 드라이버는 MariaDB Connector/J 2.7이다. MySQL의 `sslMode`를 모르므로 넣어도 TLS가 켜지지 않는다.
그래서 서버의 인증 캐시가 비어 있으면 MySQL 8.4의 기본 인증(caching_sha2_password)에서 접속이 실패하고, 캐시가 차 있으면 암호화 없이 붙는다.
암호화는 `useSsl=true&trustServerCertificate=true`로 켠다
(인증서는 확인하지 않는다. MySQL의 `sslMode=REQUIRED`와 같다).
로컬 호스트의 MySQL은 Docker Desktop에서 `host.docker.internal`로 접근할 수 있다.
같은 Docker 네트워크의 DB에는 `NOVA_FLYWAY_NETWORK`를 지정한다.
CLI는 저장소의 flyway.toml을 명시적으로 읽고 접속 변수 세 개만 컨테이너로 전달한다.

### 배포 순서와 실패 복구

배포 순서는 **같은 릴리스의 마이그레이션 성공 → 서비스 배포**다. release.yml이 이 순서를 지키고,
마이그레이션이 실패하면 어떤 서비스도 배포하지 않는다. 기존 서비스가 새 스키마에서도 동작하도록 변경하고,
컬럼 삭제 등 호환되지 않는 정리는 구버전 서비스 종료 후 별도 배포로 진행한다.

MySQL DDL은 실패 시 전체가 자동 롤백되지 않을 수 있다. 실패한 마이그레이션은 이력에 남아 다음 release도 검증에서 멈춘다.
직접 적용 절차로 붙어 실패 원인과 실제 테이블 상태를 확인하고, 상태를 고친 뒤 `flyway.sh repair`로 실패 기록을 지우고 다시 적용한다.
clean은 쓰지 않고, repair를 원인 확인 없이 자동 복구 수단으로 실행하지 않는다. `cleanDisabled=true`, `outOfOrder=false`를 유지한다.

release는 태스크가 끝나기를 30분까지 기다린다. 넘기면 job은 실패하지만 태스크는 계속 돌 수 있다.
로그(`/ecs/nova-prod-migration`)나 ECS 콘솔에서 끝난 것을 확인한 뒤 release를 다시 돌린다.

공식 문서: [프로젝트 설정](https://documentation.red-gate.com/flyway/flyway-concepts/flyway-projects),
[버전 마이그레이션](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).
