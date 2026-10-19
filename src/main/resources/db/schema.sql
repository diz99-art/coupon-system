-- 운영(prod) 테이블 정의. prod는 Hibernate가 테이블을 만들지 않고(ddl-auto: validate) 시작할 때 이 파일을 실행한다.
-- IF NOT EXISTS라 이미 있으면 건너뛴다. 이미 있는 테이블의 구조를 바꾸려면 Flyway 같은 마이그레이션 도구로 옮긴다.

CREATE TABLE IF NOT EXISTS coupon (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(255) NOT NULL,
    total_quantity  INT          NOT NULL,
    issued_quantity INT          NOT NULL,
    issue_start_at  DATETIME(6)  NOT NULL,
    issue_end_at    DATETIME(6)  NOT NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS coupon_issue (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 같은 유저는 같은 쿠폰을 한 번만 (Kafka 컨슈머 멱등 저장의 기준)
    UNIQUE KEY uk_coupon_issue_coupon_user (coupon_id, user_id),
    -- 내 발급 내역(유저별 최근 순) 조회
    KEY idx_coupon_issue_user_issued_at (user_id, issued_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
