#!/usr/bin/env bash
# Verificacao pos-deploy do Astral.
#
# ANTES (bug): a terceira checagem era curl em 8080/app/. A 8080 e' o ERP, nao o
# Astral - entao esse check passava com o Astral completamente fora do ar. Pior: ele
# dava a impressao de que a interface estava no ar quando o unico servico que
# respondia era o ERP.
#
# O Astral escuta em 8082 e so em loopback; a porta de entrada publica e' o 443, via
# nginx. Por isso os dois sao checados: o servico de tras e a entrada de frente.
set -u
fail=0
check(){ if "$@" >/dev/null 2>&1;then echo "[OK]   $*";else echo "[FAIL] $*";fail=1;fi; }
note(){ echo "[INFO] $*"; }

note "servico e porta interna"
check systemctl is-active astral-platform
check curl -fsS http://127.0.0.1:8081/actuator/health
check curl -fsS -o /dev/null http://127.0.0.1:8082/app/index.html
note "o 8082 tem de estar em 127.0.0.1; se estiver em 0.0.0.0 alguem alcanca a app sem passar pelo TLS"
if ss -lnt 2>/dev/null | grep -qE '^LISTEN.*0\.0\.0\.0:8082';then
  echo "[FAIL] 8082 escutando em 0.0.0.0 (deveria ser 127.0.0.1)";fail=1
else
  echo "[OK]   8082 restrito a loopback"
fi
note "entrada publica, com TLS e a CA local (--cacert, senao so aparece o alerta do navegador)"
check curl -fsS --cacert /etc/nginx/tls/ca.crt --resolve astral.srvcloud.cloud:443:127.0.0.1 -o /dev/null https://astral.srvcloud.cloud/app/
note "a API precisa continuar fechada para quem nao autenticou"
# Nao usar "check curl ... | grep -qx 401": o pipe engole a saida do check e o
# fail=1 acontece dentro de um subshell, entao a checagem nao reportava nada e nao
# marcava falha. Um check que nao verifica e pior do que nenhum check, porque
# parece que algo foi conferido.
code(){ curl -s -o /dev/null -m 12 -w '%{http_code}' "$@" 2>/dev/null; }
expect(){ local got="$1" want="$2" what="$3"; if [ "$got" = "$want" ];then echo "[OK]   $what -> $got";else echo "[FAIL] $what -> $got (esperado $want)";fail=1;fi; }
expect "$(code --cacert /etc/nginx/tls/ca.crt --resolve astral.srvcloud.cloud:443:127.0.0.1 https://astral.srvcloud.cloud/api/firewall/status)" 401 "firewall sem sessao"
expect "$(code --cacert /etc/nginx/tls/ca.crt --resolve astral.srvcloud.cloud:443:127.0.0.1 https://astral.srvcloud.cloud/api/auth/me)" 401 "sessao inexistente"
note "o autenticador do ATS, em 8091, tem de responder 401 e nao 403 com credencial errada"
expect "$(code -u invalido:invalido http://127.0.0.1:8091/)" 401 "proxy-auth com senha errada"
note "e a tela tem de responder, nao 404: /app/ nao resolvia indice de diretorio"
expect "$(code --cacert /etc/nginx/tls/ca.crt --resolve astral.srvcloud.cloud:443:127.0.0.1 https://astral.srvcloud.cloud/app/)" 200 "tela em /app/"
note "componentes vizinhos"
command -v traffic_ctl >/dev/null&&check systemctl is-active trafficserver
systemctl list-unit-files graylog-server.service >/dev/null 2>&1&&check systemctl is-active graylog-server

if [ "$fail" -eq 0 ];then echo "[OK]   verificacao do Astral passou inteira";else echo "[FALHA] houve verificacoes acima marcadas como FAIL";fi
exit "$fail"
