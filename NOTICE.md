# NOTICE

Astral Platform & HCI
Copyright (C) 2026 Astral Platform & HCI

Este arquivo atende à seção 4(d) da GNU Affero General Public License v3.0:
registra os avisos de copyright de terceiros e a licença de cada componente de
terceiros com o qual este projeto interage.

Este programa é software livre: você pode redistribuí-lo e modificá-lo sob os
termos da AGPLv3, publicada pela Free Software Foundation. Não há garantia
alguma. O texto completo está em [`LICENSE.md`](LICENSE.md).

---

## 📦 Nenhum código de terceiro é redistribuído neste repositório

Este é um ponto importante e frequentemente ignorado: **o repositório não empacota,
vendoriza nem redistribui o código-fonte de nenhum componente de terceiros.**

Os componentes abaixo são **instalados no sistema operacional pelo
`installbase.sh` e pelos scripts de `scripts/`**, ou são resolvidos pelo Maven e
pelo npm no momento do build. Cada um continua sob sua própria licença, que
**não é substituída** por este projeto, e cada um traz o seu próprio aviso de
copyright no pacote ou no repositório de origem.

Portanto, a lista abaixo é um mapa de dependências — não uma redistribuição.
Os avisos originais ficam onde o detentor os colocou, e nenhum foi removido.

---

## ☕ Dependências de runtime e build

| Componente | Licença | Onde é usado | Onde fica o aviso original |
|---|---|---|---|
| Spring Boot, Spring Framework, Spring Security | Apache-2.0 | `src/main/java/com/astral/main/` | repositório e `META-INF` de cada JAR |
| PostgreSQL JDBC Driver | BSD-2-Clause | `pom.xml` | repositório do driver |
| UnboundID LDAP SDK for Java (6.x) | Apache-2.0 | `pom.xml`, autenticação AD | [`docs.ldap.com`](https://docs.ldap.com/ldap-sdk/docs/) |
| React, React DOM | MIT | `frontend/src/` | `frontend/node_modules/*/LICENSE` |
| PrimeReact, PrimeIcons, PrimeFlex | MIT | `frontend/src/` | `frontend/node_modules/*/LICENSE` |
| Vite | MIT | `frontend/vite.config.js` | `frontend/node_modules/*/LICENSE` |
| Node.js | MIT | build do frontend | código-fonte do Node.js |
| Maven | Apache-2.0 | build do backend | `META-INF` do Maven |
| Eclipse Temurin JDK | GPLv2 + Classpath Exception | CI e runtime | Adler OpenJDK |
| Flask, Flask-SQLAlchemy, Flask-CORS, Jinja2 | BSD-3-Clause | `fabric/acess-report-system/` | arquivo de cada pacote |
| APScheduler | MIT | `fabric/acess-report-system/` | arquivo do pacote |
| psycopg2 | LGPL-3.0 | `fabric/acess-report-system/` | arquivo do pacote |
| Samba (bindings Python e serviços AD DC) | GPL-3.0-or-later | `fabric/samba-ad-dc/` | projeto Samba |
| Pi-hole | GPL-3.0-or-later | `fabric/DNS/` | projeto Pi-hole |
| QEMU (emulador como um todo) | GPL-2.0-only | `installbase.sh` (fase 15/16) | `COPYING` do QEMU |
| KVM (módulo do kernel) | GPL-2.0-only | `installbase.sh` (fase 15/16) | licenças do kernel Linux |
| libvirt | GPL-2.0-or-later AND LGPL-2.1 AND OFL-1.1 | `installbase.sh` (fase 15/16) | `COPYING.LIB` e `COPYING` do libvirt |
| Nginx | BSD-2-Clause | `scripts/configure-nginx.sh` | `LICENSE` do Nginx |
| Apache Traffic Server | Apache-2.0 | `scripts/configure-ats-auth.sh` | `LICENSE` do ATS |
| dnsmasq | GPL-2.0-or-later | `installbase.sh` | pacote da distribuição |

---

## ⚠️ Componentes não compatíveis com a AGPLv3 — serviços separados

Estes itens são **serviços independentes**, instalados e licenciados por fora.
Astral apenas consome os dados deles. Esta seção existe para que ninguém
confunda "Astral fala com o Graylog" com "Astral distribui o Graylog".

| Serviço | Licença | Relação com Astral |
|---|---|---|
| **Graylog (servidor)** | **SSPL-1.0** | Astral apenas envia entradas de log. O servidor Graylog **não** é software livre sob a AGPLv3 e **não** é redistribuído por este projeto. |
| Pi-hole, QEMU, KVM, libvirt, Samba | GPLv2-only / GPLv3 | Invocados como programas independentes, não vinculados. A AGPLv3 é compatível com GPLv2-only, GPLv3 e GPL-3.0-or-later. |

Se você pretende **redistribuir** o Graylog ou qualquer um dos itens acima
junto com o Astral, leia a licença daquele componente antes. A licença do
Astral não se aplica a eles.

---

## 🔗 Marcas

"Spring" e "Spring Boot" são marcas da Broadcom Inc. (anteriormente VMware, Inc.)
ou de suas afiliadas. "PostgreSQL" é marca do PostgreSQL Global Development Group.
"React" é marca da Meta Platforms, Inc. e afiliadas. "Apache" e "Apache Traffic
Server" são marcas da Apache Software Foundation. "QEMU", "KVM" e "libvirt" são
projetos da Linux Foundation. "Samba" é marca de Samba Team / Microsoft.
"React Native", "PrimeReact" e "Pi-hole" são marcas de seus respectivos
detentores. "Dell", "Intel" e "Broadcom" são marcas de seus respectivos donos.

Este projeto não é afiliado, endossado nem patrocinado por nenhuma dessas
empresas. O uso dos nomes acima é apenas para identificar as tecnologias.

---

© 2026 Astral Platform & HCI — **Criado por: Euripedes Batista de Paiva Junior**