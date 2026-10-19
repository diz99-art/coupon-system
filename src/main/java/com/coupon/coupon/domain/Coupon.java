package com.coupon.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import com.coupon.coupon.domain.CouponIssueException.ErrorCode;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 선착순 쿠폰. 총 수량만큼만 발급할 수 있고, 발급 기간 안에서만 발급된다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private int totalQuantity;

	@Column(nullable = false)
	private int issuedQuantity;

	@Column(nullable = false)
	private LocalDateTime issueStartAt;

	@Column(nullable = false)
	private LocalDateTime issueEndAt;

	/** 낙관적 락용 버전. 엔티티로 수정할 때마다 1씩 오르고, 읽을 때와 버전이 다르면 수정이 실패한다. */
	@Version
	private Long version;

	public Coupon(String name, int totalQuantity, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
		this.name = name;
		this.totalQuantity = totalQuantity;
		this.issuedQuantity = 0;
		this.issueStartAt = issueStartAt;
		this.issueEndAt = issueEndAt;
	}

	public boolean isInIssuePeriod(LocalDateTime now) {
		return !now.isBefore(issueStartAt) && !now.isAfter(issueEndAt);
	}

	public boolean hasRemainingQuantity() {
		return issuedQuantity < totalQuantity;
	}

	/** 발급 기간 안이고 남은 수량이 있는지 확인한다. 아니면 예외를 던진다. */
	public void validateIssuable(LocalDateTime now) {
		if (!isInIssuePeriod(now)) {
			throw new CouponIssueException(ErrorCode.NOT_IN_ISSUE_PERIOD);
		}
		if (!hasRemainingQuantity()) {
			throw new CouponIssueException(ErrorCode.SOLD_OUT);
		}
	}

	/** 발급 수량을 1 늘린다. 동시성 보호는 이 메서드를 부르는 서비스가 책임진다. */
	public void increaseIssuedQuantity() {
		issuedQuantity++;
	}
}
