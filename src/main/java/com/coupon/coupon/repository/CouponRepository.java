package com.coupon.coupon.repository;

import com.coupon.coupon.domain.Coupon;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	/** SELECT ... FOR UPDATE. 트랜잭션이 끝날 때까지 다른 트랜잭션은 이 행을 읽어서 잠그거나 수정할 수 없다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 발급 수량을 주어진 값으로 덮어쓴다. 버전 확인을 하지 않는다.
	 * 락 없는 서비스와 synchronized 서비스가 "읽고 → 확인하고 → 쓰는" 경쟁 조건을 그대로 보여주도록 쓴다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update Coupon c set c.issuedQuantity = :issuedQuantity where c.id = :id")
	int overwriteIssuedQuantity(@Param("id") Long id, @Param("issuedQuantity") int issuedQuantity);
}
