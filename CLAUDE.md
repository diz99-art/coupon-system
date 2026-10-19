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

## 기술 스택

- Java 21, Spring Boot 4, Gradle(Groovy)
- Spring Web, Spring Data JPA, MySQL 8.4, Spring Data Redis(Redis 7), Redisson, Spring Kafka(Kafka 4.3, KRaft), Lombok

## 명령어

- MySQL·Redis·Kafka 실행: `docker compose up -d` (종료: `docker compose down`)
- 서버 실행: `./gradlew bootRun` (기본 local 프로필: 켤 때마다 DB 테이블과 Redis 쿠폰 키 초기화)
- API 문서: 서버 실행 후 http://localhost:8080/swagger-ui.html
- 테스트: `./gradlew test` (docker compose가 떠 있어야 한다)

## 테스트 주의

- `src/test/resources/config/application.yml`이 test 프로필, 테스트 전용 토픽(coupon-issue-test)을 쓰고 Kafka 리스너와 대사 스케줄러를 끈다. 필요한 테스트만 켠다.
- DB·Redis를 쓰는 테스트는 `IntegrationTest`를 상속한다 (테스트 전후로 DB와 Redis 쿠폰 키 정리).
- Kafka 실패 처리 테스트는 내장 Kafka(@EmbeddedKafka), 측정 테스트는 docker Kafka의 측정 전용 토픽을 쓴다.
  개발 서버가 읽는 `coupon-issue` 토픽에 테스트 메시지를 넣지 않는다.
