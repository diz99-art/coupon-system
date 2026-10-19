package com.coupon.coupon.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record IssueRequest(
		@Schema(example = "1")
		@NotNull(message = "유저 id는 필수입니다.")
		@Positive(message = "유저 id는 1 이상이어야 합니다.")
		Long userId) {
}
