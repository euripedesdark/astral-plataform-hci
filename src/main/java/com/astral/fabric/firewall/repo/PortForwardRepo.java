package com.astral.fabric.firewall.repo;
import com.astral.fabric.firewall.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PortForwardRepo extends JpaRepository<PortForward,Long> {}
