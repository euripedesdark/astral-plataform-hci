#!/bin/bash
# Script de configuração Kerberos/SPNEGO para Astral Proxy (RODC)
# Uso: sudo ./setup-kerberos-auth.sh

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

if [[ $EUID -ne 0 ]]; then
    echo -e "${RED}ERRO: Este script deve ser executado como root${NC}"
    exit 1
fi

echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE}  CONFIGURAÇÃO KERBEROS/SPNEGO - ASTRAL PROXY${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""

# Detectar domínio e hostname diretamente do Samba (ignora falhas de DNS do SO)
REALM=$(samba-tool testparm --parameter-name="realm" 2>/dev/null || echo "")
if [ -n "$REALM" ]; then
    DOMAIN=${REALM,,} # Converte para minúsculas
else
    DOMAIN=$(hostname -d 2>/dev/null || echo "astral.local")
fi

NETBIOS_NAME=$(samba-tool testparm --parameter-name="netbios name" 2>/dev/null || echo "")
if [ -n "$NETBIOS_NAME" ]; then
    HOSTNAME=${NETBIOS_NAME,,}
else
    HOSTNAME=$(hostname -s)
fi

FQDN="${HOSTNAME}.${DOMAIN}"
KEYTAB_PATH="/etc/astral/certs/proxy/proxy.keytab"
SPN="HTTP/${FQDN}"

echo -e "${YELLOW}Detectando configuração:${NC}"
echo -e "  Domínio: ${GREEN}${DOMAIN}${NC}"
echo -e "  Hostname: ${GREEN}${FQDN}${NC}"
echo -e "  SPN: ${GREEN}${SPN}${NC}"
echo ""

# Verificar o Papel do Servidor com base no banco de dados do AD local
echo -e "${YELLOW}Verificando papel do servidor Samba...${NC}"
SERVER_ROLE=$(samba-tool testparm --parameter-name="server role" 2>/dev/null)
IS_RODC=false

if [ "$SERVER_ROLE" = "active directory domain controller" ]; then
    # Diferencia RODC de DC principal pelo ID nativo do grupo (521 = RODC, 516 = Writable DC)
    if samba-tool user show "${NETBIOS_NAME}$" 2>/dev/null | grep -q "primaryGroupID: 521"; then
        echo -e "${GREEN}✓ RODC detectado${NC}"
        IS_RODC=true
    else
        echo -e "${YELLOW}✓ Domain Controller (Gravável) detectado${NC}"
    fi
elif [ "$SERVER_ROLE" = "member server" ]; then
    echo -e "${YELLOW}✓ Servidor Membro detectado${NC}"
else
    echo -e "${RED}⚠ Samba não configurado ou papel desconhecido ($SERVER_ROLE)${NC}"
fi
echo ""

# Passo 1: Instalar dependências Kerberos
echo -e "${GREEN}[1/6] Instalando dependências Kerberos...${NC}"
if command -v apt-get &> /dev/null; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y krb5-user libpam-krb5 libsasl2-modules-gssapi-heimdal
elif command -v dnf &> /dev/null; then
    dnf install -y krb5-workstation krb5-libs pam_krb5
elif command -v pacman &> /dev/null; then
    pacman -S --noconfirm krb5 pam_krb5
else
    echo -e "${RED}ERRO: Gerenciador de pacotes não suportado${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Dependências instaladas${NC}"
echo ""

# Passo 2: Configurar krb5.conf
echo -e "${GREEN}[2/6] Configurando /etc/krb5.conf...${NC}"
cat > /etc/krb5.conf <<EOF
[libdefaults]
    default_realm = ${DOMAIN^^}
    dns_lookup_realm = false
    dns_lookup_kdc = true
    ticket_lifetime = 24h
    renew_lifetime = 7d
    forwardable = true
    rdns = false
    default_tgs_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96 rc4-hmac
    default_tkt_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96 rc4-hmac
    permitted_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96 rc4-hmac

[realms]
    ${DOMAIN^^} = {
        kdc = ${FQDN}
        admin_server = ${FQDN}
        default_domain = ${DOMAIN}
    }

[domain_realm]
    .${DOMAIN} = ${DOMAIN^^}
    ${DOMAIN} = ${DOMAIN^^}

[logging]
    default = FILE:/var/log/krb5libs.log
    kdc = FILE:/var/log/krb5kdc.log
    admin_server = FILE:/var/log/kadmind.log
EOF
echo -e "${GREEN}✓ krb5.conf configurado${NC}"
echo ""

# Passo 3: Criar diretório e gerar keytab
echo -e "${GREEN}[3/6] Gerando keytab para ${SPN}...${NC}"
mkdir -p /etc/astral/certs/proxy
chown -R root:astral /etc/astral/certs/proxy
chmod 2770 /etc/astral/certs/proxy

# Se for RODC, precisamos usar samba-tool ou pedir ao DC principal
if [ "$IS_RODC" = true ]; then
    echo -e "${YELLOW}RODC detectado - gerando keytab localmente...${NC}"

    # Criar usuário de serviço no Samba (se não existir)
    SERVICE_USER="proxy-svc"
    if ! samba-tool user list 2>/dev/null | grep -q "^${SERVICE_USER}$"; then
        echo -e "${YELLOW}Criando usuário de serviço ${SERVICE_USER}...${NC}"
        read -p "Digite a senha para o usuário ${SERVICE_USER}: " -s SVC_PASS
        echo ""
        samba-tool user create "${SERVICE_USER}" "${SVC_PASS}" --given-name="Proxy Service" --surname="Account"
        samba-tool user setexpiry "${SERVICE_USER}" --noexpiry
    fi

    # Adicionar SPN ao usuário
    echo -e "${YELLOW}Adicionando SPN ${SPN} ao usuário ${SERVICE_USER}...${NC}"
    samba-tool spn add "${SPN}" "${SERVICE_USER}" 2>/dev/null || echo "SPN já existe"

    # Exportar keytab
    echo -e "${YELLOW}Exportando keytab...${NC}"
    samba-tool domain exportkeytab "${KEYTAB_PATH}" --principal="${SPN}"

else
    # Não é RODC - usar kadmin ou solicitar ao administrador
    echo -e "${YELLOW}Sistema não é RODC - solicitando credenciais administrativas...${NC}"
    read -p "Digite o usuário administrador do AD (ex: Administrator): " ADMIN_USER

    # Criar principal e exportar keytab
    echo -e "${YELLOW}Criando principal e exportando keytab via kadmin...${NC}"
    cat > /tmp/krb_commands.txt <<EOF
addprinc -randkey ${SPN}
ktadd -k ${KEYTAB_PATH} ${SPN}
quit
EOF

    kadmin -p "${ADMIN_USER}" -q "ktadd -k ${KEYTAB_PATH} ${SPN}" || {
        echo -e "${RED}ERRO: Falha ao criar keytab. Verifique as credenciais.${NC}"
        exit 1
    }
fi

# Ajustar permissões do keytab
chmod 0640 "${KEYTAB_PATH}"
chown root:astral "${KEYTAB_PATH}"
echo -e "${GREEN}✓ Keytab gerado em ${KEYTAB_PATH}${NC}"
echo ""

# Passo 4: Adicionar dependências Maven ao projeto
echo -e "${GREEN}[4/6] Atualizando pom.xml com spring-security-kerberos...${NC}"
PROXY_DIR="/home/$(logname 2>/dev/null || echo "$SUDO_USER")/astral-plataform-hci/fabric/proxy"

if [ ! -d "$PROXY_DIR" ]; then
    echo -e "${RED}ERRO: Diretório do proxy não encontrado em ${PROXY_DIR}${NC}"
    exit 1
fi

# Backup do pom.xml
cp "${PROXY_DIR}/pom.xml" "${PROXY_DIR}/pom.xml.backup"

# Inserir dependências antes do </dependencies>
sed -i '/<\/dependencies>/i \
        <!-- Kerberos/SPNEGO Authentication -->\
        <dependency>\
            <groupId>org.springframework.security.kerberos</groupId>\
            <artifactId>spring-security-kerberos-web</artifactId>\
            <version>2.0.0</version>\
        </dependency>\
        <dependency>\
            <groupId>org.springframework.security.kerberos</groupId>\
            <artifactId>spring-security-kerberos-client</artifactId>\
            <version>2.0.0</version>\
        </dependency>' "${PROXY_DIR}/pom.xml"

echo -e "${GREEN}✓ Dependências adicionadas ao pom.xml${NC}"
echo ""

# Passo 5: Criar configuração Spring Security Kerberos
echo -e "${GREEN}[5/6] Criando configuração Spring Security Kerberos...${NC}"

cat > "${PROXY_DIR}/src/main/java/com/astral/proxy/config/KerberosSecurityConfig.java" <<'EOF'
package com.astral.proxy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider;
import org.springframework.security.kerberos.authentication.sun.SunJaasKerberosTicketValidator;
import org.springframework.security.kerberos.web.authentication.SpnegoAuthenticationProcessingFilter;
import org.springframework.security.kerberos.web.authentication.SpnegoEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.core.io.Resource;

@Configuration
@EnableWebSecurity
public class KerberosSecurityConfig {

    @Value("${astral.kerberos.service-principal}")
    private String servicePrincipal;

    @Value("${astral.kerberos.keytab-location}")
    private String keytabLocation;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .exceptionHandling()
                .authenticationEntryPoint(spnegoEntryPoint())
                .and()
            .authorizeHttpRequests()
                .requestMatchers("/proxy/api/auth/kerberos/**", "/install.html", "/api/stream").permitAll()
                .anyRequest().authenticated()
                .and()
            .addFilterBefore(spnegoAuthenticationProcessingFilter(authenticationManager(http)),
                BasicAuthenticationFilter.class)
            .csrf().disable();

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        return http.getSharedObject(AuthenticationManagerBuilder.class)
                .authenticationProvider(kerberosServiceAuthenticationProvider())
                .build();
    }

    @Bean
    public SpnegoEntryPoint spnegoEntryPoint() {
        return new SpnegoEntryPoint("/login");
    }

    @Bean
    public SpnegoAuthenticationProcessingFilter spnegoAuthenticationProcessingFilter(
            AuthenticationManager authenticationManager) {
        SpnegoAuthenticationProcessingFilter filter = new SpnegoAuthenticationProcessingFilter();
        filter.setAuthenticationManager(authenticationManager);
        return filter;
    }

    @Bean
    public KerberosServiceAuthenticationProvider kerberosServiceAuthenticationProvider() {
        KerberosServiceAuthenticationProvider provider = new KerberosServiceAuthenticationProvider();
        provider.setTicketValidator(sunJaasKerberosTicketValidator());
        provider.setUserDetailsService(kerberosUserDetailsService());
        return provider;
    }

    @Bean
    public SunJaasKerberosTicketValidator sunJaasKerberosTicketValidator() {
        SunJaasKerberosTicketValidator ticketValidator = new SunJaasKerberosTicketValidator();
        ticketValidator.setServicePrincipal(servicePrincipal);
        Resource keytabResource = new FileSystemResource(keytabLocation);
        ticketValidator.setKeyTabLocation(keytabResource);
        ticketValidator.setDebug(true);
        return ticketValidator;
    }

    @Bean
    public KerberosUserDetailsService kerberosUserDetailsService() {
        return new KerberosUserDetailsService();
    }
}
EOF

# Criar UserDetailsService para Kerberos
cat > "${PROXY_DIR}/src/main/java/com/astral/proxy/config/KerberosUserDetailsService.java" <<'EOF'
package com.astral.proxy.config;

import com.astral.proxy.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

@Service
public class KerberosUserDetailsService implements UserDetailsService {

    @Autowired
    private AuthService authService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Remove o domínio do username (ex: user@DOMAIN.LOCAL -> user)
        String cleanUsername = username.contains("@") ? username.split("@")[0] : username;

        // Buscar grupo do usuário no banco
        String grupo = "users";
        try {
            grupo = authService.getGrupoFromCatalog(cleanUsername);
        } catch (Exception e) {
            // Se não encontrar, usa grupo padrão
        }

        return new User(
            cleanUsername,
            "", // Senha não é usada com Kerberos
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + grupo.toUpperCase()))
        );
    }
}
EOF

echo -e "${GREEN}✓ Configuração Spring Security Kerberos criada${NC}"
echo ""

# Passo 6: Atualizar AuthService e ProxyApiController
echo -e "${GREEN}[6/6] Atualizando AuthService e API para suportar Kerberos...${NC}"

# Inserir método no AuthService antes do último }
sed -i '/^}$/i \
    public String getGrupoFromCatalog(String username) {\
        try {\
            return st.db.queryForObject(\
                "select grupo from autenticacao where usuario = ? limit 1",\
                String.class,\
                username\
            );\
        } catch (Exception e) {\
            return "users";\
        }\
    }' "${PROXY_DIR}/src/main/java/com/astral/proxy/service/AuthService.java"

# Inserir endpoints antes do último } do ProxyApiController
sed -i '/^}$/i \
    @GetMapping("/auth/kerberos/status")\
    public Mono<Map<String,Object>> kerberosStatus() {\
        return call(() -> {\
            Map<String,Object> m = new HashMap<>();\
            m.put("enabled", true);\
            m.put("servicePrincipal", "HTTP/" + java.net.InetAddress.getLocalHost().getCanonicalHostName());\
            m.put("keytabExists", java.nio.file.Files.exists(java.nio.file.Paths.get("/etc/astral/certs/proxy/proxy.keytab")));\
            return m;\
        });\
    }\
\
    @GetMapping("/auth/kerberos/test")\
    public Mono<Map<String,Object>> testKerberos(@RequestParam String username) {\
        return call(() -> {\
            try {\
                String grupo = auth.getGrupoFromCatalog(username);\
                Map<String,Object> result = new HashMap<>();\
                result.put("success", true);\
                result.put("username", username);\
                result.put("grupo", grupo);\
                result.put("message", "Usuário encontrado no catálogo");\
                return result;\
            } catch (Exception e) {\
                Map<String,Object> result = new HashMap<>();\
                result.put("success", false);\
                result.put("error", e.getMessage());\
                return result;\
            }\
        });\
    }' "${PROXY_DIR}/src/main/java/com/astral/proxy/api/ProxyApiController.java"

# Atualizar application.properties
cat >> "${PROXY_DIR}/src/main/resources/application.properties" <<EOF

# Kerberos/SPNEGO Configuration
astral.kerberos.service-principal=${SPN}
astral.kerberos.keytab-location=${KEYTAB_PATH}
astral.kerberos.debug=true
EOF

echo -e "${GREEN}✓ AuthService e API atualizados${NC}"
echo ""

# Passo final: Compilar e reiniciar
echo -e "${BLUE}===========================================${NC}"
echo -e "${GREEN}CONFIGURAÇÃO CONCLUÍDA!${NC}"
echo -e "${BLUE}===========================================${NC}"
echo ""
echo -e "${YELLOW}Próximos passos:${NC}"
echo "1. Compile o projeto:"
echo "   cd ${PROXY_DIR}"
echo "   mvn clean package"
echo ""
