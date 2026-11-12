package com.ayush.urlshortener.repository;

import java.util.Optional;

import com.ayush.urlshortener.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for users. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByUsername(String username);

	boolean existsByUsername(String username);

}
