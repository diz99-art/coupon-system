package com.coupon.coupon.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record CouponCreateRequest(
		@Schema(example = "선착순 100장 쿠폰")
		@NotBlank(message = "쿠폰 이름은 비어 있을 수 없습니다.")
		@Size(max = 100, message = "쿠폰 이름은 100자 이하여야 합니다.")
		String name,

		@Schema(example = "100")
		@NotNull(message = "총 수량은 필수입니다.")
		@Positive(message = "총 수량은 1 이상이어야 합니다.")
		@Max(value = 1_000_000, message = "총 수량은 1,000,000 이하여야 합니다.")
		Integer totalQuantity,

		@Schema(example = "2026-10-20T10:00:00")
		@NotNull(message = "발급 시작 시각은 필수입니다.")
		LocalDateTime issueStartAt,

		@Schema(example = "2026-10-20T11:00:00")
		@NotNull(message = "발급 종료 시각은 필수입니다.")
		LocalDateTime issueEndAt) {

	/** 두 필드를 함께 봐야 하는 검증. 시각이 비어 있으면 @NotNull이 따로 잡으므로 여기서는 통과시킨다. */
	@JsonIgnore
	@AssertTrue(message = "발급 종료 시각은 시작 시각보다 늦어야 합니다.")
	public boolean isIssueEndAfterStart() {
		return issueStartAt == null || issueEndAt == null || issueEndAt.isAfter(issueStartAt);
	}
}
