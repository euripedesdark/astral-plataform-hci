package com.astral.firewall.repo;
import com.astral.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RateLimitPolicyRepo extends JpaRepository<RateLimitPolicy,Long> {}
