package com.coupon.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

	/** 발급 수량을 1 늘린다. 동시성 처리는 하지 않는다 (1단계). */
	public void increaseIssuedQuantity() {
		issuedQuantity++;
	}
}
