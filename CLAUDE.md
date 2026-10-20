# 선착순 쿠폰 발급 시스템 (coupon-system)

백엔드 포트폴리오용 선착순 쿠폰 발급 시스템.

## 사용자와 목적

- 사용자는 Java/Spring 초보다. 목적은 학습과 면접 대비다.
- 설명은 한국어로 한다.

## 작업 방식

- 환경 설정, 설정 파일, 배포 같은 반복 작업은 직접 해도 된다.
- 쿠폰 발급 로직, 동시성 처리(락, Redis, Kafka) 같은 핵심 코드도 완성된 코드를 바로 작성한다.
  그 코드에 쓰인 개념과 왜 이렇게 짰는지를 함께 설명한다.
- 사용자가 직접 짠 코드는 리뷰하고 개선점을 설명한다.

## 보고 방식

- 작업 중 설명은 짧게 한다. 자세한 설명(개념, 이유, 측정값, 겪은 문제)은 `docs/` 문서에 남기고 경로만 알려준다.
- 마지막 "Claude 전달용" 보고(형식은 사용자 전역 CLAUDE.md)에 아래 세 항목을 더한다. 표는 쓰지 않는다.
  사용자는 이 보고를 다른 Claude에게 가져가 설명을 듣는다 → 이 보고만 읽어도 맥락이 통하게, 용어는 정확한 이름으로 쓴다.
  - [설계 결정] 결정마다 한 줄씩: 무엇을 / 어떻게 / 왜 / 다른 선택지
  - [새 개념] 이번 작업에서 처음 나온 기술 용어 목록
  - [문서] 관련 docs 경로와 GitHub 링크

## 기술 스택

- Java 21, Spring Boot 4, Gradle(Groovy)
- Spring Web, Spring Data JPA, MySQL 8.4, Spring Data Redis(Redis 7), Redisson, Spring Kafka(Kafka 4.3, KRaft), Lombok

## 명령어

- 처음 한 번: `cp .env.example .env` (비밀번호 등 환경변수. `.env`는 git에 올리지 않는다)
- MySQL·Redis·Kafka 실행: `docker compose up -d` (종료: `docker compose down`)
- 서버 실행: `./gradlew bootRun` (기본 local 프로필: 켤 때마다 DB 테이블과 Redis 쿠폰 키 초기화)
- API 문서: 서버 실행 후 http://localhost:8080/swagger-ui.html
- 서버 2대 + nginx + 모니터링: `docker compose --profile cluster up -d --build` (8090 → 2대, 8091 → 1대, bench 프로필)
  - Grafana http://localhost:3000 (대시보드는 `monitoring/grafana/gen_dashboard.py`로 만들어 `dashboards/coupon.json`에 저장), Prometheus http://localhost:9090
  - 앱의 관리용 포트는 8081 (`/actuator/prometheus`, `/actuator/health`). nginx로는 보이지 않는다.
  - 앱 컨테이너만 다시 만들면 nginx가 옛 주소를 기억하므로 `docker restart coupon-nginx`
- 부하 테스트: `bench/run.sh <서버 수 1|2> <사용자 수> [방식...]`, 전체 측정은 `bench/all.sh` (k6는 컨테이너로 실행)
  - `bench/trace-request.sh`: 발급 요청 1건의 SQL(general log)과 Redis 명령(MONITOR). `bench/cache-bench.sh <이름>`: 워밍업 후 3회 측정과 SQL·Redis 명령 수
  - `bench/monitor.sh <반복> [수량]`: Redis+Kafka 10,000명을 반복하고 Grafana 캡처. `bench/reconcile-check.sh [분]`: 대사 작업이 매분 한 서버만 도는지 로그로 확인
- 테스트: `./gradlew test` (docker compose가 떠 있어야 한다)

## 프로필

- local(기본): 개발 서버. 켤 때마다 DB 테이블, Redis 쿠폰 키, Kafka 발급 토픽의 남은 메시지를 비운다.
- test: 테스트. docker: 컨테이너 실행(ddl-auto update, 토픽 coupon-issue-cluster). bench: 비교 API(/bench) 켜기.
- prod: 배포용. ddl-auto validate + `db/schema.sql`(테이블을 바꾸면 이 파일과 엔티티를 함께 고친다), bench·Swagger 꺼짐, 관리 포트 8081.
- 접속 정보와 비밀번호는 코드나 yml에 쓰지 않고 환경변수(`DB_PASSWORD` 등)로 받는다. 새 값이 생기면 `.env.example`에도 추가한다.

## 쿠폰 정보 캐시

- 발급 판정은 Redis 해시 `coupon:{id}:info`(총 수량, 기간)를 Lua 안에서 읽는다. DB의 Coupon을 바꾸는 코드는 반드시 `CouponService`를 거쳐 Redis 사본도 바꾼다 (생성: 커밋 뒤 올림, 수정: 원자적 확인 후 변경, 롤백 시 삭제).
- DB를 직접 고쳤다면 `coupon:{id}:info` 키를 지운다. 다음 발급 요청이 DB에서 다시 읽는다.

## 스케줄 작업

- 대사 작업은 cron(매분 0초) + ShedLock(Redis 키 `job-lock:coupon:<이름>`)으로 한 서버만 실행한다. 새 스케줄 작업에도 `@SchedulerLock`을 붙인다.

## CI

- GitHub Actions(`.github/workflows/ci.yml`)가 push·PR마다 `docker compose up -d --wait mysql redis kafka` 후 `./gradlew test`를 돌린다.
- main push는 이어서 `image`(ARM64 이미지 → `ghcr.io/diz99-art/coupon-system:<커밋 해시>`)와 `deploy`(platform-infra의 `k8s/apps/coupon/kustomization.yaml` newTag 변경)가 돈다. ArgoCD가 감지해 k3s 클러스터에 배포한다.
  → **main에 push하면 약 5분 뒤 클러스터에 배포된다.** 깨진 코드를 main에 바로 올리지 않는다 (테스트가 실패하면 배포되지 않는다). 롤백은 platform-infra에서 봇 커밋을 `git revert`.

## 테스트 주의

- 테스트, 부하 테스트(bench), 개발 서버, 서버 2대(cluster)는 같은 MySQL DB와 Redis를 쓴다. 동시에 돌리지 않는다 (테스트가 테이블을 다시 만든다). 테스트 전에 `docker compose --profile cluster stop app1 app2`.

- `src/test/resources/config/application.yml`이 test 프로필, 테스트 전용 토픽(coupon-issue-test)을 쓰고 Kafka 리스너와 대사 스케줄러를 끈다. 필요한 테스트만 켠다.
- DB·Redis를 쓰는 테스트는 `IntegrationTest`를 상속한다 (테스트 전후로 DB와 Redis 쿠폰 키 정리).
- Kafka 실패 처리 테스트는 내장 Kafka(@EmbeddedKafka), 측정 테스트는 docker Kafka의 측정 전용 토픽을 쓴다.
  개발 서버가 읽는 `coupon-issue` 토픽에 테스트 메시지를 넣지 않는다.
