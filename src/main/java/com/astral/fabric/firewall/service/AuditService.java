package com.astral.fabric.firewall.service;
import com.astral.fabric.firewall.model.AuditLog; import com.astral.fabric.firewall.repo.AuditLogRepo;
import org.springframework.stereotype.Service;
@Service
public class AuditService {
private final AuditLogRepo repo; public AuditService(AuditLogRepo r){repo=r;}
public void log(String user,String type,String id,String action,String diff){
AuditLog a=new AuditLog(); a.username=user==null?"admin":user; a.entityType=type; a.entityId=id; a.action=action; a.diffJson=diff; repo.save(a); }
}
