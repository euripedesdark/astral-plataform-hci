#!/usr/bin/env bash
# =============================================================================
# Astral Platform - instalador unico
# =============================================================================
# Este arquivo E o instalador. Nao existe instalador 2.
#
# DECISAO DE ARQUITETURA (2026-09-28, fechada com o dono):
#   O instalador e bash puro e instala direto. NAO chama Python e NAO chama Java.
#
#   Por que, ja que install.py e Installer.java faziam o mesmo trabalho:
#     1. O unico instalador que ja rodou em producao foi bash. Os outros dois
#        nunca funcionaram.
#     2. install.py subia um Flask em 0.0.0.0:5000 com POST /api/setup-db sem
#        autenticacao, criando role SUPERUSER. Isso nao e instalador, e um painel
#        de administracao sem porta.
#     3. Os dois geravam codigo-fonte (pom.xml e varios .java) a partir de
#        strings embutidas, de uma versao mais velha da plataforma. Rodar
#        sobrescrevia o codigo de trabalho com codigo morto. Um instalador NUNCA
#        deve escrever o codigo que ele proprio compila.
#     4. O que sobrava de util nos dois era: garantir JDK, instalar pacote, criar
#        role e banco, escrever 4 unit files. Bash faz os quatro e nao interpreta
#        nada no meio.
#
#   Este script NUNCA escreve pom.xml nem nenhum .java. Se algum dia precisar
#   gerar codigo, o codigo gerado vai no repositorio e passa por revisao.
# =============================================================================
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Se o script foi executado de fora do repo, ROOT aponta para o lugar errado e
# todo resto quebra em silencio (build sem frontend, jar inexistente). Falhar
# aqui e melhor do que instalar um artefato invalido.
[[ -f "$ROOT/pom.xml" ]] || {
  printf '[erro] Este script precisa estar em <repo>/scripts/ install-astral.sh.\n' >&2
  printf '        Ele espera o pom.xml em %s e nao achou.\n' "$ROOT" >&2
  exit 1
}

DB_NAME="${ASTRAL_DB_NAME:-astral}"
DB_USER="${ASTRAL_DB_USER:-astral}"
ENVF=/etc/astral/astral.env
UNIT=/etc/systemd/system/astral-platform.service
APP_DIR=/opt/astral-platform
SVC_USER=astral

DRY=0
ASSUME_YES=0
PHASES=()

# --- saida -------------------------------------------------------------------
if [[ -t 1 ]]; then B=$'\033[1m'; R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; N=$'\033[0m'
else B=""; R=""; G=""; Y=""; N=""; fi
log()  { printf '%s[ ]%s %s\n' "$B" "$N" "$*"; }
ok()   { printf '%s[ok]%s %s\n' "$G" "$N" "$*"; }
warn() { printf '%s[av]%s %s\n' "$Y" "$N" "$*" >&2; }
die()  { printf '%s[erro]%s %s\n' "$R" "$N" "$*" >&2; exit 1; }
head1(){ printf '\n%s== %s ==%s\n' "$B" "$*" "$N"; }

# --- execucao ----------------------------------------------------------------
# Tudo que muda o sistema passa por aqui. Com --dry-run nada acontece, o que
# transforma o dry-run emonzinho em confiavel: nao existe caminho que nao passe
# por run().
run() {
  if (( DRY )); then
    printf '%s[dry]%s %s\n' "$Y" "$N" "$*"
    return 0
  fi
  "$@"
}

# shell=disable=SC2086  (os argumentos vem propositalmente de variaveis)
runsh() {
  if (( DRY )); then
    printf '%s[dry]%s %s\n' "$Y" "$N" "$*"
    return 0
  fi
  sh -c "$*"
}

confirm() {
  (( ASSUME_YES || DRY )) && return 0
  local resp
  read -r -p "$(printf '%sConfirma? [s/N] %s' "$Y" "$N")" resp
  [[ "${resp,,}" == s* ]] || die "cancelado pelo usuario"
}

usage() {
  cat <<'EOF'
Uso: sudo ./scripts/install-astral.sh [opcoes] [fases...]

FASES (padrao: todas, nesta ordem)
  deps      garantir JDK 21, Maven, PostgreSQL e Node
  db        criar role e banco, se nao existirem (sem SUPERUSER)
  build     npm build do frontend + mvn package
  deploy    instalar o jar em /opt/astral-platform
  unit      escrever a unit systemd e reiniciar o servico
  firewall  inserir as regras do Astral (nunca faz flush)
  ats       configurar o auth do ATS, se ele existir

OPCOES
  -n, --dry-run   mostra o que faria e nao muda nada
  -y, --yes       nao pergunta confirmacao
  -h, --help      esta ajuda

EXEMPLOS
  sudo ./scripts/install-astral.sh --dry-run
  sudo ./scripts/install-astral.sh build deploy unit
  sudo ./scripts/install-astral.sh db -y
EOF
}

# --- argumentos --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--dry-run) DRY=1 ;;
    -y|--yes)     ASSUME_YES=1 ;;
    -h|--help)    usage; exit 0 ;;
    deps|db|build|deploy|unit|firewall|ats) PHASES+=("$1") ;;
    *) die "opcao desconhecida: $1 (use --help)" ;;
  esac
  shift
done
[[ ${#PHASES[@]} -gt 0 ]] || PHASES=(deps db build deploy unit firewall ats)

(( EUID == 0 )) || die "execute com sudo"

# =============================================================================
# deps
# =============================================================================
have_java21() {
  # Aceita tanto /usr/bin/java apontando pra 21 quanto um jdk-21 em /usr/lib/jvm.
  java -version 2>&1 | grep -qE '"21\.' && return 0
  [[ -x /usr/bin/javac ]] || return 1
  javac -version 2>&1 | grep -qE '^javac 21\.' && return 0
  return 1
}

fase_deps() {
  head1 "deps: pacotes e toolchain"

  if have_java21; then
    ok "JDK 21 presente: $(java -version 2>&1 | head -1)"
  else
    warn "JDK 21 ausente"
    if (( DRY )); then
      run dnf install -y java-21-openjdk-devel
    else
      # java-21-openjdk-devel traz o JDK completo. Se o repo nao tiver, o
      # Oracle JDK e o plano B, que e o que o servidor ja usa.
      dnf install -y java-21-openjdk-devel >/dev/null 2>&1 \
        || dnf install -y "https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.rpm" >/dev/null
      have_java21 || die "instalei o pacote mas ainda nao acho javac 21. Rode: dnf install -y java-21-openjdk-devel"
      ok "JDK 21 instalado"
    fi
  fi

  if command -v mvn >/dev/null; then
    ok "Maven presente: $(mvn -v 2>/dev/null | head -1)"
  else
    warn "Maven ausente"; run dnf install -y maven
  fi

  if rpm -q postgresql-server >/dev/null 2>&1; then
    ok "PostgreSQL Server instalado"
  else
    warn "postgresql-server ausente"; run dnf install -y postgresql-server
  fi

  if command -v npm >/dev/null; then
    ok "Node presente: $(node -v)"
  else
    warn "nodejs ausente"; run dnf install -y nodejs
  fi

  # Deliberadamente NAO instalamos nginx e NAO mexemos em firewalld.
  # Os dois instaladores antigos faziam dnf install nginx e
  # "systemctl stop firewalld ufw; systemctl disable firewalld ufw".
  # Desligar o firewall nao e parte de instalar uma aplicacao web, e o
  # servidor tem o firewalld desligado a mao e com regra feita na mao.
  # Quem quiser o nginx no pool de portas, instala e configura a parte.
  ok "nginx e firewalld: nao tocados, de proposito"
}

# =============================================================================
# db
# =============================================================================
# O psql aqui e o ponto que o instalador antigo estava quebrado. A versao
# antiga fazia PGPASSWORD="" psql -h 127.0.0.1 -U postgres, o que falha com
# "fe_sendauth: no password supplied", e ela prendia o erro em 2>/dev/null.
# O resultado era que "banco nao existe" e "nao consegui autenticar" viravam a
# mesma mensagem, e o script acusava um banco que existia.
#
# Aqui os dois erros sao separados: o peer primeiro (nao usa senha e nao
# depende do pg_hba local), e so se ele falhar tentamos TCP com senha.
psql_peer() { runuser -u postgres -- psql -v ON_ERROR_STOP=1 -tAc "$1" 2>/dev/null; }

psql_super() {
  local q="$1" out
  if out="$(psql_peer "$q")"; then printf '%s' "$out"; return 0; fi
  # Fallback: TCP com senha. Se nao autenticar, devolve erro de auth, e o
  # chamador trata como problema de credencial, nunca como "banco nao existe".
  if out="$(PGPASSWORD="${ASTRAL_DB_SUPERPASS:-}" psql -h 127.0.0.1 \
              -U postgres -d postgres -v ON_ERROR_STOP=1 -tAc "$q" 2>/dev/null)"; then
    printf '%s' "$out"; return 0
  fi
  return 1
}

fase_db() {
  head1 "db: role e banco"

  if ! psql_super "select 1" >/dev/null; then
    die "nao consegui falar com o postgres como superusuario.
     Isso e FALHA DE CREDENCIAL, e nao 'banco nao existe'. As duas coisas
    reamsaram erro antes porque o erro era engolido.
     Se voce e root na maquina, o esperado e que 'runuser -u postgres -- psql -tAc \"select 1\"'
     funcione sem senha. Se nao funcionar, o pg_hba local esta bloqueado e o
     problema e do postgres, nao do instalador.
     Alternativa: export ASTRAL_DB_SUPERPASS=<senha do postgres> e rode de novo."
  fi
  ok "postgres responde como superusuario (peer)"

  # --- role: SEM SUPERUSER ----------------------------------------------------
  # Os dois instaladores antigos criavam o role como SUPERUSER (install.py:253,
  # Installer.java:166). O role astral em producao hoje e super=false e a
  # FirewallProxyController depende de minimo privilegio. Este script mantem
  # super=false, e nao mexe em super se o role ja existir.
  if [[ "$(psql_super "select rolsuper from pg_roles where rolname='$DB_USER'")" == "t" ]]; then
    warn "o role '$DB_USER' e SUPERUSER. O instalador nao mexe nisso, porque"
    warn "baixar privilegio de um role em uso derruba o ERP em producao."
    warn "Se quiser: ALTER ROLE $DB_USER NOSUPERUSER;  (so com o ERP parado)"
  fi

  DB_PASS="${ASTRAL_DB_PASSWORD:-}"
  if [[ "$(psql_super "select 1 from pg_roles where rolname='$DB_USER'")" ]]; then
    ok "role '$DB_USER' existe"
  else
    [[ -n "$DB_PASS" ]] || DB_PASS="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
    warn "role '$DB_USER' nao existe, vou criar com super=false"
    confirm "criar o role '$DB_USER'?"
    if (( DRY )); then
      echo "[dry] CREATE ROLE $DB_USER LOGIN NOSUPERUSER PASSWORD '***'"
    else
      psql_super "CREATE ROLE \"$DB_USER\" LOGIN NOSUPERUSER PASSWORD '$DB_PASS'" >/dev/null \
        || die "falha ao criar o role"
      ok "role '$DB_USER' criado com super=false"
    fi
  fi

  if [[ "$(psql_super "select 1 from pg_database where datname='$DB_NAME'")" ]]; then
    ok "banco '$DB_NAME' existe"
  else
    warn "banco '$DB_NAME' nao existe, vou criar"
    confirm "criar o banco '$DB_NAME'?"
    if (( DRY )); then
      echo "[dry] CREATE DATABASE $DB_NAME OWNER $DB_USER"
    else
      psql_super "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\" ENCODING 'UTF8'" >/dev/null \
        || die "falha ao criar o banco"
      ok "banco '$DB_NAME' criado, dono '$DB_USER'"
    fi
  fi

  # Este script NUNCA dropa banco. Nem o outro. Se alguem precisa recriar o
  # banco do Astral, e uma decisao manual, com o servico parado e backup feito.
  ok "nenhum banco foi dropado (este script nao sabe dropar)"
}

# =============================================================================
# build
# =============================================================================
fase_build() {
  head1 "build: frontend e jar"

  cd "$ROOT"

  # O fonte do Vite vive em frontend// e a saida vai para static/app/, que e
  # o que o pom empacota. Se o build do frontend nao rodou, o jar sobe sem a
  # tela e o /app/ devolve 404. O guard abaixo corta o build do jar antes
  # de produzir um artefato invalido.
  if command -v npm >/dev/null && [[ -f frontend/package.json ]]; then
    if [[ ! -f frontend/index.html ]]; then
      die "frontend Vite sem index.html em src/main/resources/static/react.
       O build foi interrompido para nao gerar um artefato invalido."
    fi
    log "npm install + npm run build em frontend/"
    runsh "cd '$ROOT/frontend' && npm install && npm run build"
  else
    warn "sem npm ou sem package.json: o jar vai sem o build do frontend"
  fi

  # O guard do index.html nao garante que o build passou. Confere que a saida
  # existe antes de gastar 2 minutos de Maven.
  if [[ -d frontend ]]; then
    if (( ! DRY )) && [[ ! -f src/main/resources/static/app/index.html ]]; then
      die "o npm nao produziu static/app/index.html. Sem isso o /app/ vai
       devolver 404 em producao. Nao prossigo para o mvn."
    fi
  fi

  log "mvn -DskipTests clean package"
  runsh "cd '$ROOT' && mvn -DskipTests clean package"

  # O build roda como root (o script precisa de root), mas o repo e do usuario.
  # Sem isto o target/ fica com ownership de root e o PROXIMO build do usuario
  # falha com "Failed to delete target/...jar". Ja aconteceu.
  if (( ! DRY )); then
    REPO_OWNER="$(stat -c '%U:%G' "$ROOT")"
    chown -R "$REPO_OWNER" "$ROOT/target" 2>/dev/null || true
    ok "target/ devolvido para $REPO_OWNER"
  fi

  # Selecao deterministica do jar. O artifactId do pom e "astral-proxy", nome
  # herdado de quando o projeto so era proxy; o MESMO arquivo e instalado como
  # astral-platform.jar, que e o que a unit executa. Nao e bug, mas parece, e
  # alguem vai "corrigir" um dia. Se aparecer mais de um jar elegivel, e
  # melhor falhar do que escolher o primeiro que o find devolveu.
  mapfile -t JARS < <(find "$ROOT/target" -maxdepth 1 -type f -name '*.jar' \
                      ! -name '*sources*' ! -name '*javadoc*' ! -name '*-plain*' 2>/dev/null | sort)
  case "${#JARS[@]}" in
    1) JAR_NAME="$(basename "${JARS[0]}")" ;;
    0) die "nenhum jar em target/ apos o build" ;;
    *) die "tem ${#JARS[@]} jars em target/, nao sei qual instalar:
       ${JARS[*]}
     O pom deveria produzir exatamente um. Limpe com: mvn clean" ;;
  esac
  ok "jar: $JAR_NAME (artifactId do pom e legado: 'astral-proxy' e a plataforma)"
}

# =============================================================================
# deploy
# =============================================================================
fase_deploy() {
  head1 "deploy: jar em $APP_DIR"

  mapfile -t DFIND < <(find "$ROOT/target" -maxdepth 1 -type f -name '*.jar' \
                        ! -name '*sources*' ! -name '*javadoc*' ! -name '*-plain*' 2>/dev/null | sort)
  [[ ${#DFIND[@]} -eq 1 ]] || die "esperava 1 jar em target/, achei ${#DFIND[@]}: ${DFIND[*]:-nenhum}"
  JAR="${DFIND[0]}"

  # usuario de servico dedicated: a app faz proxy HTTP para o firewall, fala
  # LDAP e Postgres. Nada disso precisa de root.
  if ! id -u "$SVC_USER" >/dev/null 2>&1; then
    log "criando usuario de servico '$SVC_USER'"
    run useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$SVC_USER"
  else
    ok "usuario de servico '$SVC_USER' existe"
  fi

  log "backing up do jar atual, se houver"
  if (( ! DRY )) && [[ -f "$APP_DIR/astral-platform.jar" ]]; then
    cp -a "$APP_DIR/astral-platform.jar" \
       "$APP_DIR/astral-platform.jar.bak.$(date +%s)"
  fi

  run install -d -m 0755 -o root -g "$SVC_USER" "$APP_DIR"
  run install -d -m 0750 -o root -g "$SVC_USER" /etc/astral
  run install -m 0640 -o root -g "$SVC_USER" "$JAR" "$APP_DIR/astral-platform.jar"

  # --- env: popula o que falta, sem sobrescrever o que o usuario ja configurou -
  head1 "deploy: /etc/astral/astral.env"
  if (( DRY )); then
    echo "[dry] populo $ENVF com o que faltar (ASTRAL_DB_URL, ASTRAL_DB_USER, ASTRAL_DB_PASSWORD)"
  else
    if [[ -e "$ENVF" ]]; then
      cp -a "$ENVF" "$ENVF.bak.$(date +%s)"
      ok "backup do env em $ENVF.bak.$(date +%s)"
    fi
    touch "$ENVF"; chmod 0640 "$ENVF"; chown root:"$SVC_USER" "$ENVF"
    have(){ grep -qE "^[[:space:]]*$1=" "$ENVF"; }
    ensure(){ local k=$1 v=$2; have "$k" || printf '%s=%s\n' "$k" "$v" >>"$ENVF"; }
    ensure ASTRAL_DB_URL          "jdbc:postgresql://127.0.0.1:5432/$DB_NAME"
    ensure ASTRAL_DB_USER         "$DB_USER"
    ensure ASTRAL_DB_PASSWORD     "${DB_PASS:-${ASTRAL_DB_PASSWORD:-}}"
    ensure ASTRAL_AUTH_POSTGRES_URL "jdbc:postgresql://127.0.0.1:5432/$DB_NAME"

    # Sem senha, a app sobe apontando para credencial vazia e cai em
    # "Unable to determine Dialect without JDBC metadata", que e o crash-loop de
    # 5 em 5 segundos do Restart=on-failure. Melhor falhar aqui.
    [[ -n "$(grep -E '^ASTRAL_DB_PASSWORD=.+' "$ENVF")" ]] || die "$ENVF sem ASTRAL_DB_PASSWORD.
     A app sobe apontando pra credencial vazia e entra em crash-loop.
     Rode assim: ASTRAL_DB_PASSWORD=<senha> sudo -E ./scripts/install-astral.sh deploy unit"
    ok "env populado sem sobrescrever o que ja existia"
  fi

  # backup dos arquivos que o resto do sistema le
  for f in /etc/astral/ad.properties /etc/trafficserver/plugin.config; do
    if [[ -e "$f" ]]; then
      log "backup de $f"
      run cp -a "$f" "$f.astral.bak.$(date +%s)"
    fi
  done
}

# =============================================================================
# unit
# =============================================================================
fase_unit() {
  head1 "unit: systemd"

  # Esta unit e a dona do servico. O Installer.java tambem escrevia uma unit
  # para o mesmo servico, no formato antigo (root, porta 8081, jar
  # astral-platform-1.0.0.jar que nao existe, sem EnvironmentFile). Dois donos
  # da mesma unit e como nasce drift, e o formato antigo sobrescreveria este.
  # Se voce esta lendo isto procurando o outro instalador: ele foi removido.
  if (( DRY )); then
    echo "[dry] escrevo $UNIT (User=$SVC_USER, EnvironmentFile=$ENVF, porta 8082)"
  else
    cat >"$UNIT" <<EOF
[Unit]
Description=Astral Platform & HCI
After=network-online.target postgresql.service
Wants=network-online.target
[Service]
Type=simple
# Antes era root. A app faz proxy HTTP para o firewall, fala LDAP e Postgres:
# nada disso precisa de root.
User=$SVC_USER
Group=$SVC_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENVF
ExecStart=/usr/bin/java -jar $APP_DIR/astral-platform.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
StandardOutput=journal
StandardError=journal
[Install]
WantedBy=multi-user.target
EOF
    ok "unit escrita em $UNIT"
  fi

  run systemctl daemon-reload
  run systemctl enable astral-platform.service

  # enable --now NAO reinicia uma unit que ja esta active: o processo continua
  # com o jar antigo em memoria e o deploy "passa" sem trocar nada. E enable +
  # restart. Este restart derruba a plataforma por alguns segundos; e o servico
  # do proprio Astral, entao nao ha outro cliente para avisar.
  warn "reiniciando astral-platform (indisponibilidade de poucos segundos)"
  confirm "reiniciar a plataforma agora?"
  run systemctl restart astral-platform.service
}

# =============================================================================
# firewall
# =============================================================================
fase_firewall() {
  head1 "firewall: regras do Astral"

  # Delega ao script proprio, que so INSERE regras. Nao faz flush, nao mexe em
  # policy e nao desliga firewalld. Se um dia ele mudar de comportamento, este
  # e o ponto a revisar.
  if [[ -x "$ROOT/scripts/configure-system-firewall.sh" ]]; then
    log "chamando scripts/configure-system-firewall.sh (so insere)"
    run "$ROOT/scripts/configure-system-firewall.sh"
  else
    warn "scripts/configure-system-firewall.sh ausente; nada feito"
  fi
  ok "firewall nao foi flushado, so recebeu as regras do Astral"
}

# =============================================================================
# ats
# =============================================================================
fase_ats() {
  head1 "ats: auth do Traffic Server"

  if ! command -v traffic_ctl >/dev/null && [[ ! -x /opt/trafficserver/bin/traffic_ctl ]]; then
    ok "ATS nao instalado; nada a fazer"
    return 0
  fi
  if [[ -x "$ROOT/scripts/configure-ats-auth.sh" ]]; then
    log "chamando scripts/configure-ats-auth.sh"
    run "$ROOT/scripts/configure-ats-auth.sh"
  else
    warn "scripts/configure-ats-auth.sh ausente; nada feito"
  fi
  # Nota: o remap.config do ATS esta vazio e a 3128 e usada em forward. Um
  # remap so teria sentido em reverso, atras do nginx. Isso e do pool da IA do
  # servidor, nao do instalador.
}

# =============================================================================
main() {
  if (( DRY )); then
    warn "DRY-RUN: nada sera alterado. Fases: ${PHASES[*]}"
  fi

  for p in "${PHASES[@]}"; do
    case "$p" in
      deps)    fase_deps ;;
      db)      fase_db ;;
      build)   fase_build ;;
      deploy)  fase_deploy ;;
      unit)    fase_unit ;;
      firewall) fase_firewall ;;
      ats)     fase_ats ;;
    esac
  done

  head1 "fim"
  if (( DRY )); then
    echo "Nada foi alterado. Rode sem --dry-run para aplicar."
  else
    ok "instalacao concluida"
    echo
    echo "Confere com: sudo $ROOT/scripts/verify-astral.sh"
    echo "Logs da plataforma: journalctl -u astral-platform -f"
  fi
}

main "$@"
