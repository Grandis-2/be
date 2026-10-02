# shop 스키마와 Flyway

Flyway Community CLI를 Docker로 실행한다. Gradle 모듈이나 상시 실행 서비스는 없다.
MySQL 8.4.11과 Flyway 11.20.0으로 검증하며, 유료 비교/생성 기능은 사용하지 않는다.

## 관리 범위

- `migrations/`: 실제 적용의 정본이자 유일한 shop 정의. 최초 파일은 도입 당시 shop 정의와 설계 주석을 보존한다.
- `flyway.toml`: SQL 경로, shop 스키마, 검증 및 실행 규칙. 접속 비밀값은 넣지 않는다.
- `../docs/external-mock-schema.sql`: 도입 당시 Mock 정의를 보존한 참고 자료. 최신 정의는 Mock 저장소에서 관리한다.

두 스키마가 같은 RDS에 있어도 이 프로젝트는 `shop.flyway_schema_history`만 관리한다.
Mock 저장소는 `external_mock`의 마이그레이션과 이력 테이블을 별도로 관리한다.
스키마 설정 자체는 권한 경계가 아니므로 마이그레이션 계정에 `external_mock` 변경 권한을 주지 않는다.
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
영속 볼륨을 사용하지 않으며 기존 로컬 DB나 RDS에는 접속하지 않는다.
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

## RDS 초기 준비 및 실행

RDS 관리자 계정으로 shop을 먼저 만든다. Mock DB 초기 준비는 해당 저장소의 절차를 따른다.

```sql
CREATE DATABASE shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

별도 마이그레이션 계정에 shop 범위의 CREATE, ALTER, DROP, INDEX, REFERENCES,
SELECT, INSERT, UPDATE, DELETE 권한을 부여한다. 서비스 계정에는 필요한 DML 권한만 부여한다.
batch의 Mock 조회는 별도로 해당 테이블의 SELECT 권한을 부여한다.
DB 자동 생성과 자동 baseline은 비활성화되어 있어 초기 준비 누락이나 기존 테이블을 조용히 건너뛰지 않는다.

RDS에 접속 가능한 실행 환경에서 다음 변수를 주입한다. 비밀번호는 시크릿 저장소나
로컬 셸 입력으로 제공하고 파일/명령 인자에 직접 기록하지 않는다.

```sh
export FLYWAY_URL='jdbc:mysql://YOUR_RDS_ENDPOINT:3306/shop?sslMode=REQUIRED'
export FLYWAY_USER='nova_migrator'
# FLYWAY_PASSWORD는 실행 환경의 시크릿으로 주입
bash flyway-project/flyway.sh info
bash flyway-project/flyway.sh migrate
bash flyway-project/flyway.sh validate
```

운영 TLS는 RDS CA를 신뢰하도록 실행 환경을 준비한 뒤 `sslMode=VERIFY_IDENTITY` 사용을 권장한다.
로컬 호스트의 MySQL은 Docker Desktop에서 `host.docker.internal`로 접근할 수 있다.
같은 Docker 네트워크의 DB에는 `NOVA_FLYWAY_NETWORK`를 지정한다.
CLI는 저장소의 flyway.toml을 명시적으로 읽고 접속 변수 세 개만 컨테이너로 전달한다.

배포 순서는 **같은 릴리스의 마이그레이션 성공 → 서비스 배포**다. 마이그레이션 실패 시
새 서비스 배포를 중단한다. 기존 서비스가 새 스키마에서도 동작하도록 변경하고,
컬럼 삭제 등 호환되지 않는 정리는 구버전 서비스 종료 후 별도 배포로 진행한다.
현재 release.yml은 ECR 이미지 발행까지만 수행하므로 RDS 실행은 연결하지 않았다.
향후 RDS 네트워크에 접근 가능한 배포 작업에서 위 명령을 실행해야 한다.

MySQL DDL은 실패 시 전체가 자동 롤백되지 않을 수 있다. 실패 원인과 실제 테이블 상태를
확인한 뒤 복구한다. clean이나 repair를 자동 복구 수단으로 실행하지 않는다.
`cleanDisabled=true`, `outOfOrder=false`를 유지한다.

공식 문서: [프로젝트 설정](https://documentation.red-gate.com/flyway/flyway-concepts/flyway-projects),
[버전 마이그레이션](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).
