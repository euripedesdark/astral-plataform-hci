package com.astral.firewall.repo;
import com.astral.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FirewallRuleRepo extends JpaRepository<FirewallRule,Long> { java.util.List<FirewallRule> findByChainOrderByPriority(String chain); }
