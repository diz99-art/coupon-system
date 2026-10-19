package com.coupon.coupon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 어떤 유저가 어떤 쿠폰을 언제 받았는지 남기는 발급 기록. 같은 쿠폰을 같은 유저가 두 번 받을 수 없다. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_coupon_issue_coupon_user", columnNames = {"couponId", "userId"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long couponId;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private LocalDateTime issuedAt;

	public CouponIssue(Long couponId, Long userId, LocalDateTime issuedAt) {
		this.couponId = couponId;
		this.userId = userId;
		this.issuedAt = issuedAt;
	}
}
