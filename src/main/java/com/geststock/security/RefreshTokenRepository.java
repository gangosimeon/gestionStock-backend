package com.geststock.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/** Verrou en écriture : deux renouvellements simultanés du même jeton sont sérialisés. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :hash")
	Optional<RefreshToken> findByHashForUpdate(@Param("hash") String hash);

	Optional<RefreshToken> findByTokenHash(String hash);

	@Modifying(flushAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :at, t.revokedReason = :reason
			where t.familyId = :familyId and t.revokedAt is null""")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") RefreshToken.RevocationReason reason,
			@Param("at") Instant at);

	@Modifying(flushAutomatically = true)
	@Query("""
			update RefreshToken t set t.revokedAt = :at, t.revokedReason = :reason
			where t.userId = :userId and t.revokedAt is null""")
	int revokeAllOfUser(@Param("userId") UUID userId, @Param("reason") RefreshToken.RevocationReason reason,
			@Param("at") Instant at);

}
