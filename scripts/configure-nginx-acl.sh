#!/usr/bin/env bash
# Garante que o Astral esta' publicado em 443 e que a rota de ACL existe.
#
# Este e' o script que se roda quando "a pagina nao abre" ou quando o servidor
# foi reconstruido. Ele NAO escreve config: quem escreve e o configure-nginx.sh.
# Aqui so' se verifica a ausencia e se chama quem sabe escrever -- porque dois
# arquivos-fonte para a mesma porta e' como a config de ontem e a de hoje
# divergem.
#
# IDEMPOTENTE. Rodar dez vezes faz a mesma coisa que rodar uma.
#
#   1. /etc/nginx/conf.d/astral.conf nao existe?   -> cria a rota 443 (TLS inclusive)
#   2. a marca '# astral-acl:v1' nao existe?       -> regenera com a rota de ACL
#   3. nginx -t + reload                           -> sempre, porque a proxima
#      pessoa que rodar este script quer saber se o nginx esta' de pe'
#
# Uso:  sudo scripts/configure-nginx-acl.sh
set -Eeuo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONF_DIR="${NGINX_CONF_DIR:-/etc/nginx/conf.d}"
ASTRAL_CONF="$CONF_DIR/astral.conf"
MARCADOR='# astral-acl:v1'
SUDO=""
[[ $EUID -ne 0 ]]&&SUDO="sudo"

command -v nginx >/dev/null||{ echo "[ERRO] nginx ausente.";exit 1; }

# --- 1) rota 443 -----------------------------------------------------------------
# O requisito e' "passa na 443 usando nginx e cria a rota se nao houver".
# Ausente o arquivo, o proprio configure-nginx.sh gera CA, certificado, upstream
# e server block -- e devolve a rota pronta.
if [[ ! -f "$ASTRAL_CONF" ]];then
  echo "[INFO] $ASTRAL_CONF ausente: criando a rota 443 do Astral."
  $SUDO bash "$DIR/configure-nginx.sh"
else
  echo "[OK] rota 443 presente: $ASTRAL_CONF"
fi

# --- 2) rota de ACL ---------------------------------------------------------------
# A marca e' o contrato entre os dois scripts: sem ela, o auth_request nao esta'
# configurado e toda decisao de ACL fica só no banco, sem ninguem consultando.
if ! grep -qs "$MARCADOR" "$ASTRAL_CONF";then
  echo "[INFO] marca '$MARCADOR' ausente em astral.conf: regenerando com auth_request."
  $SUDO bash "$DIR/configure-nginx.sh"
fi

# A pagina de bloqueio e' parte da rota: sem ela, o negado vira 403 cru do
# Nginx no meio da politica -- que e' exatamente a experiencia que o 403
# customizado existe para evitar.
if [[ ! -f "${ASTRAL_ACL_PAGE:-/etc/nginx/astral/bloqueado.html}" ]];then
  echo "[INFO] pagina de bloqueio ausente: regenerando."
  $SUDO bash "$DIR/configure-nginx.sh"
fi

# --- 3) confere e recarrega --------------------------------------------------------
if grep -qs "$MARCADOR" "$ASTRAL_CONF";then
  echo "[OK] rota de ACL presente (auth_request -> /api/v1/acl/check)."
else
  echo "[ERRO] rota de ACL continua ausente em $ASTRAL_CONF"
  exit 1
fi

if ! $SUDO nginx -t;then
  echo "[ERRO] nginx -t reprovou. A config nao foi recarregada."
  exit 1
fi
$SUDO systemctl reload nginx 2>/dev/null||$SUDO systemctl restart nginx

# --- 4) prova de vida -------------------------------------------------------------
# Testa o subrequest direto: 401 (sem sessao) e' a resposta ESPERADA de um motor
# que nao conhece quem perguntou. 403 significa politica, 200 significa acesso
# publico (login/assets). Qualquer outra coisa e' problema de rota.
CODIGO="$(curl -sk -o /dev/null -w '%{http_code}' \
  -H 'X-Original-Host: astral.srvcloud.cloud' \
  -H 'X-Original-URI: /' \
  https://127.0.0.1/api/v1/acl/check || echo 000)"
case "$CODIGO" in
  200|401|403) echo "[OK] /api/v1/acl/check respondeu $CODIGO em 443." ;;
  *) echo "[AVISO] /api/v1/acl/check devolveu $CODIGO."
     echo "        Duas causas, e as duas sao' a mesma raiz: a aplicacao em 8082"
     echo "        esta' fora, ou e' o build antigo, sem o endpoint de ACL. Enquanto"
     echo "        isso a rota ja' esta' instalada e o auth_request responde fail-"
     echo "        closed (nada passa) ate' a aplicacao nova subir." ;;
esac

echo "[OK] 443 TLS + ACL prontos. CA: ${ASTRAL_TLS_DIR:-/etc/nginx/tls}/ca.crt"
