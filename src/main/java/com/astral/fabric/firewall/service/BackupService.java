package com.astral.fabric.firewall.service;
import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service;
import java.nio.file.*; import java.util.*;
@Service
public class BackupService {
@Value("${spring.datasource.username}") private String user;
@Value("${spring.datasource.password:}") private String pass;
private static final String TABLES="-t 'firewall_%' -t 'port_forward' -t 'masquerade_rule' -t 'zone*' -t 'host_group*' -t 'port_group*' -t 'schedule' -t 'rate_limit_policy' -t 'auto_ban_rule' -t 'threat_list' -t 'audit_log'";
public String exportSql(){try{ProcessBuilder pb=new ProcessBuilder("bash","-c",
"PGSSLCERT=/etc/astral/certs/client-astral.crt PGSSLKEY=/etc/astral/certs/client-astral.pk8 PGSSLROOTCERT=/etc/astral/certs/root.crt PGSSLMODE=verify-ca pg_dump -h 127.0.0.1 -U "+user+" -d astral --clean --if-exists --no-owner "+TABLES);
Process p=pb.start();String out=new String(p.getInputStream().readAllBytes());p.waitFor();return out;}catch(Exception e){return "-- ERRO: "+e.getMessage();}}
public boolean restoreSql(String sql){try{Path t=Files.createTempFile("fwrestore",".sql");Files.writeString(t,sql);
ProcessBuilder pb=new ProcessBuilder("bash","-c","PGSSLCERT=/etc/astral/certs/client-astral.crt PGSSLKEY=/etc/astral/certs/client-astral.pk8 PGSSLROOTCERT=/etc/astral/certs/root.crt PGSSLMODE=verify-ca psql -h 127.0.0.1 -U "+user+" -d astral --single-transaction -v ON_ERROR_STOP=1 -f "+t.toAbsolutePath());
Process p=pb.start();p.getInputStream().readAllBytes();int c=p.waitFor();Files.deleteIfExists(t);
return c==0;}catch(Exception e){return false;}}
}
