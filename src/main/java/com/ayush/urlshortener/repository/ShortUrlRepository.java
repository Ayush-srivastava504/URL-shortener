package com.ayush.urlshortener.repository;

import java.util.Optional;

import com.ayush.urlshortener.domain.ShortUrl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Data access for short links. */
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

	Optional<ShortUrl> findByShortCode(String shortCode);

	boolean existsByShortCode(String shortCode);

	Page<ShortUrl> findByOwnerIdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

	@Modifying
	@Query("update ShortUrl s set s.clickCount = s.clickCount + :delta where s.shortCode = :code")
	int incrementClicks(@Param("code") String code, @Param("delta") long delta);

}
