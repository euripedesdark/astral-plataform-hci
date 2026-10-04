#!/usr/bin/env bash
# Publica o Astral em 443 TLS, com o ERP ja em 80 pelo bloco erp.conf.
#
# POR QUE ESTE ARQUIVO EXISTE: a config vivia so em /etc/nginx/conf.d/astral.conf.
# Se o servidor fosse reconstruido, o TLS do Astral se perderia junto com o disco e
# ninguem teria o texto de volta. Este script e a fonte, /etc e a copia instalada.
# (Ja aconteceu: em 04/10 o host subiu so com erp.conf e sem /etc/nginx/tls.)
#
# IDEMPOTENTE, e de proposito: a CA e a chave do servidor sao geradas SO se ainda nao
# existirem. Regerar a cada execucao trocaria o certificado e o cliente que ja tinha
# a CA na truststore passaria a ver alerta - que e exatamente o que o mkcert evita
# fazendo a mesma coisa.
#
# Inclui a rota de ACL (auth_request -> /api/v1/acl/check) marcada com
# '# astral-acl:v1'. O scripts/configure-nginx-acl.sh procura essa marca para
# saber se a rota existe; se nao existir, ele chama este script. Manter a
# geracao num so' lugar evita dois scripts brigando pelo mesmo arquivo.
set -Eeuo pipefail
TLS_DIR="${ASTRAL_TLS_DIR:-/etc/nginx/tls}"
CONF_DIR="${NGINX_CONF_DIR:-/etc/nginx/conf.d}"
ASTRAL_CONF="$CONF_DIR/astral.conf"
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
  echo "[INFO] CA local ja existe; mantendo para nao invalidar a confianca dos clientes."
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
extendedKeyUsage = serverAuth,clientAuth
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
  rm -f "$TLS_DIR/astral.csr"
else
  echo "[INFO] Certificado do Astral ja existe; mantendo."
fi
openssl verify -CAfile "$TLS_DIR/ca.crt" "$TLS_DIR/astral.crt" >/dev/null

# --- map do WebSocket -------------------------------------------------------------
# O $connection_upgrade so existe se algum .conf declarar o map. Aqui ele e'
# declarado SO se nenhum outro arquivo do conf.d ja' declarou: declarar duas vezes
# faz o nginx recusar subir com "duplicate map", e nao declarar nenhuma vez faz o
# nginx recusar com "unknown variable". O astral.conf antigo e ignorado na busca
# de proposito -- ele esta' prestes a ser sobrescrito, e o map dele vem junto.
TEM_MAP=0
for f in "$CONF_DIR"/*.conf; do
  [[ -e "$f" ]]||continue
  [[ "$f" == "$ASTRAL_CONF" ]]&&continue
  grep -qs 'connection_upgrade' "$f"&&TEM_MAP=1
done

# --- pagina de bloqueio -----------------------------------------------------------
# Escrita em arquivo proprio (e nao num 'return' do nginx) porque o nginx nao
# trata aspas simples como aspas: o HTML quebraria em "invalid number of
# arguments". Arquivo tambem e' editavel pelo operador sem regerar a rota.
# 0755, nao 0750: o worker do nginx (usuario 'nginx') precisa TRAVERSAR o
# diretorio para ler o arquivo. Com 0750 root:root o open() do worker falha
# com EACCES e o error_page cai no 403 generico do nginx -- a pagina de
# bloqueio existia, era o diretorio que estava trancado por dentro.
install -d -m 0755 /etc/nginx/astral
cat > /etc/nginx/astral/bloqueado.html <<'HTML'
<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Acesso bloqueado</title>
<style>
  body{background:#0b0f1a;color:#e6edf3;font-family:system-ui,-apple-system,sans-serif;
       display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
  main{max-width:36rem;padding:2rem;border:1px solid #1f2a44;border-radius:12px;background:#111827}
  h1{font-size:1.35rem;color:#ff6b6b;margin-top:0}
  p{line-height:1.6;color:#9fb0cc}
  footer{margin-top:1.5rem;font-size:.85rem;color:#5b6b8c}
</style>
</head>
<body>
<main>
  <h1>Acesso negado pela política de navegação</h1>
  <p>Este endereço está bloqueado para o seu usuário ou grupo. A decisão é
     automática e leva menos de 2 segundos; nenhuma credencial é necessária
     para ela acontecer.</p>
  <p>Se você acha que isto é um engano, fale com o administrador da rede
     informando o endereço e o horário.</p>
  <footer>Astral HCI-NGFW &middot; motor de ACL</footer>
</main>
</body>
</html>
HTML

# --- config ------------------------------------------------------------------------
[[ -f "$ASTRAL_CONF" ]]&&cp -a "$ASTRAL_CONF" "$ASTRAL_CONF.astral.bak.$(date +%s)"
{
if [[ $TEM_MAP -eq 0 ]];then
cat <<'EOF'
# Gerado por scripts/configure-nginx.sh. Nao editar a mao: edite o script.
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
EOF
else
echo "# Gerado por scripts/configure-nginx.sh. Nao editar a mao: edite o script."
fi
cat <<'EOF'
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

    # =====================================================================
    # astral-acl:v1  -- rota do auth_request (Task 3). Nao remover a marca:
    # scripts/configure-nginx-acl.sh procura por ela para saber se a rota
    # de ACL existe no servidor.
    #
    # O Nginx nao decide nada aqui. Ele manda UMA subrequisicao por pagina
    # para o motor e obedece: 2xx libera, 403 bloqueia, 401 nao tem identidade
    # (nao ha fail-open: se o motor nao responde, o subrequest nao e' 2xx e a
    # pagina nao abre).
    # =====================================================================
    location = /acl-check {
        internal;

        proxy_pass http://astral_app/api/v1/acl/check;
        proxy_pass_request_body off;
        proxy_set_header Content-Length  "";

        # OBRIGATORIO, nao opcional. Sem esta linha o Nginx usa o default
        # `proxy_set_header Host $proxy_host` -- e $proxy_host de um upstream
        # nomeado e' o PROPRIO nome do upstream: "astral_app". Tomcat recusa
        # Host com underscore (400, "character [_] is never valid in a domain
        # name"), o auth_request so' aceita 200/401/403, e o resultado e' que
        # TODA pagina da porta 443 vira 500 -- inclusive as publicas, num
        # erro que nao tem nada a ver com ACL. Descoberto assim: o subrequest
        # respondia 400 e o error log enchia de "auth request unexpected
        # status: 400".
        proxy_set_header Host              $host;
        proxy_set_header X-Original-Host $host;
        proxy_set_header X-Original-URI  $request_uri;
        proxy_set_header X-Real-IP       $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # O motor responde em ~2ms. 3s e' folga para o pior caso de banco;
        # passar disso seria transformar um cache fora em pagina que nao abre.
        proxy_read_timeout 3s;
        proxy_connect_timeout 2s;
    }

    # Pagina de bloqueio: 403 do motor vira algo que o usuario entende, e nao
    # o 403 cru do Nginx. O arquivo e' escrito por este script em
    # /etc/nginx/astral/bloqueado.html -- em arquivo proprio, porque o nginx
    # nao aceita aspas simples no 'return' e o HTML tem espaco e attributes.
    # So' o auth_request cai aqui: resposta 403 vinda da aplicacao nao e'
    # interceptada (proxy_intercept_errors fica desligado).
    location / {
        # Rota de ACL. O que e' publico (login, assets, SPA, swagger) e'
        # decidido DENTRO do motor, em /api/v1/acl/check, para que a lista de
        # excecoes viva num lugar so' e seja testavel sem subir o Nginx.
        auth_request  /acl-check;
        error_page 403 /bloqueado.html;

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

    location = /bloqueado.html {
        internal;
        root /etc/nginx/astral;
        charset utf-8;
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
} > "$ASTRAL_CONF"

# Testa ANTES de recarregar. Se o teste falhar, restaura o backup: uma config
# nova que nao sobe e' pior que a config antiga que ja' estava la'.
if ! nginx -t; then
  BAK="$(ls -1t "$ASTRAL_CONF".astral.bak.* 2>/dev/null | head -1)"
  if [[ -n "$BAK" ]];then
    echo "[ERRO] nginx -t reprovou a config nova; restaurando $BAK"
    cp -a "$BAK" "$ASTRAL_CONF"
    nginx -t
  fi
  exit 1
fi
systemctl reload nginx 2>/dev/null||systemctl restart nginx
echo "[OK] Astral em 443 (TLS) + rota de ACL em /acl-check. Instale a CA no cliente para nao ver alerta:"
echo "     $TLS_DIR/ca.crt"
