package com.paycore.merchant;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends CrudRepository<ApiKey, String> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

    /** Revocation is an UPDATE guarded by ownership; returns 0 if the key is not this merchant's or already revoked. */
    @Modifying
    @Query("UPDATE api_keys SET revoked_at = :at WHERE id = :id AND merchant_id = :merchantId AND revoked_at IS NULL")
    int revoke(@Param("id") String id, @Param("merchantId") String merchantId, @Param("at") Instant at);

    /** last_used_at is written at most once per window (the WHERE clause), so authenticating a key does not cost a DB write on every request: on Neon/Render. */
    @Modifying
    @Query("UPDATE api_keys SET last_used_at = :now WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :threshold)")
    int touchIfStale(@Param("id") String id, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
