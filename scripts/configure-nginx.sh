#!/usr/bin/env bash
# Publica o Astral em 443 TLS, com o ERP ja em 80 pelo bloco brasil-saas-app.conf.
#
# POR QUE ESTE ARQUIVO EXISTE: a config vivia so em /etc/nginx/conf.d/astral.conf.
# Se o servidor fosse reconstruido, o TLS do Astral se perderia junto com o disco e
# ninguem teria o texto de volta. Este script e a fonte, /etc e a copia instalada.
#
# IDEMPOTENTE, e de proposito: a CA e a chave do servidor sao geradas SO se ainda nao
# existirem. Regerar a cada execucao trocaria o certificado e o cliente que ja tinha
# a CA na truststore passaria a ver alerta - que e exatamente o que o mkcert evita
# fazendo a mesma coisa.
set -Eeuo pipefail
TLS_DIR="${ASTRAL_TLS_DIR:-/etc/nginx/tls}"
CONF_DIR="${NGINX_CONF_DIR:-/etc/nginx/conf.d}"
ASTRAL_HOST="${ASTRAL_HOST:-astral.srvcloud.cloud}"
command -v nginx >/dev/null||{ echo "[ERRO] nginx ausente.";exit 1; }
command -v openssl >/dev/null||{ echo "[ERRO] openssl ausente.";exit 1; }
install -d -m 0750 "$TLS_DIR" "$CONF_DIR"

# --- CA local (so se ainda nao existir) -------------------------------------------
# Nao se pode assinar com a CA do Samba: a chave dela esta no sam.ldb, e o key.pem do
# diretorio /var/lib/samba/private/tls e' a chave do HOST, nao da CA. Ja testado:
# "CA certificate and CA private key do not match". Por isso a CA e' propria.
if [[ ! -f "$TLS_DIR/ca.crt" || ! -f "$TLS_DIR/ca.key" ]];then
  echo "[INFO] Gerando CA local (so nesta primeira vez)."
  openssl genrsa -out "$TLS_DIR/ca.key" 4096 2>/dev/null
  chmod 0600 "$TLS_DIR/ca.key"
  openssl req -x509 -new -key "$TLS_DIR/ca.key" -days 3650 -sha256 -out "$TLS_DIR/ca.crt" \
    -subj "/O=Brasil SaaS Astral/CN=Astral Local CA" 2>/dev/null
  chmod 0644 "$TLS_DIR/ca.crt"
else
  echo "[INFO] CA local ja existe; mantendo para nao invalidar a confianza dos clientes."
fi

# --- certificado do Astral --------------------------------------------------------
# Os SANs cobrem os nomes plausiveis e os IPs porque ainda nao ha um nome definido
# para o Astral, e nao quero que o certificado seja o proximo problema.
if [[ ! -f "$TLS_DIR/astral.crt" || ! -f "$TLS_DIR/astral.key" ]];then
  echo "[INFO] Emitindo certificado para $ASTRAL_HOST."
  CNF="$(mktemp)"; trap 'rm -f "$CNF"' EXIT
  cat >"$CNF" <<EOF
[req]
distinguished_name = dn
req_extensions     = ext
prompt             = no
[dn]
CN = $ASTRAL_HOST
O  = Brasil SaaS
[ext]
basicConstraints = CA:FALSE
keyUsage         = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName   = @alt
[alt]
DNS.1 = $ASTRAL_HOST
DNS.2 = dc-erp.srvcloud.cloud
DNS.3 = srvcloud.cloud
DNS.4 = astral
DNS.5 = localhost
IP.1  = 127.0.0.1
IP.2  = 192.168.2.10
IP.3  = 192.168.2.159
EOF
  openssl genrsa -out "$TLS_DIR/astral.key" 2048 2>/dev/null
  chmod 0600 "$TLS_DIR/astral.key"
  openssl req -new -key "$TLS_DIR/astral.key" -out "$TLS_DIR/astral.csr" -config "$CNF"
  openssl x509 -req -in "$TLS_DIR/astral.csr" -CA "$TLS_DIR/ca.crt" -CAkey "$TLS_DIR/ca.key" \
    -CAcreateserial -days 825 -sha256 -extfile "$CNF" -extensions ext -out "$TLS_DIR/astral.crt" 2>/dev/null
  chmod 0644 "$TLS_DIR/astral.crt"
  rm -f "$TLS_DIR/astral.csr"
else
  echo "[INFO] Certificado do Astral ja existe; mantendo."
fi
openssl verify -CAfile "$TLS_DIR/ca.crt" "$TLS_DIR/astral.crt" >/dev/null

# --- config ------------------------------------------------------------------------
# O $connection_upgrade NAO e definido aqui de proposito: o map ja existe em
# brasil-saas-app.conf. Map e global no contexto http, e declarar o mesmo nome duas
# vezes faz o nginx recusar subir com "duplicate map".
[[ -f "$CONF_DIR/astral.conf" ]]&&cp -a "$CONF_DIR/astral.conf" "$CONF_DIR/astral.conf.astral.bak.$(date +%s)"
cat >"$CONF_DIR/astral.conf" <<'EOF'
# Gerado por scripts/configure-nginx.sh. Nao editar a mao: edite o script.
upstream astral_app {
    server 127.0.0.1:8082;
    keepalive 16;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name _;

    ssl_certificate     /etc/nginx/tls/astral.crt;
    ssl_certificate_key /etc/nginx/tls/astral.key;

    ssl_protocols             TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache         shared:astral_tls:10m;
    ssl_session_timeout       1d;

    client_max_body_size 16m;

    proxy_connect_timeout 30s;
    proxy_send_timeout    300s;
    proxy_read_timeout    300s;

    location / {
        proxy_pass http://astral_app;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        # E' este header que liga o cookie de sessao em secure. Combinado com
        # server.forward-headers-strategy=framework no application.properties.
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

# 81 -> 443, para ninguem ficar preso a porta antiga.
server {
    listen 81;
    listen [::]:81;
    server_name _;
    return 301 https://$host$request_uri;
}
EOF

nginx -t
systemctl reload nginx 2>/dev/null||systemctl restart nginx
echo "[OK] Astral em 443 (TLS), ERP em 80. Instale a CA no cliente para nao ver alerta:"
echo "     $TLS_DIR/ca.crt"
