# Contribuindo para o Astral Platform & HCI

Obrigado pelo interesse em contribuir com o Astral Platform & HCI! Este
documento fornece diretrizes para contribuir com o projeto.

## Código de Conduta

Este projeto e todos os participantes são regidos pelo
[Código de Conduta](../../CODE_OF_CONDUCT.md). Ao participar, você é esperado
para respeitar este código.

## Como Posso Contribuir?

### Reportando Bugs

Antes de criar um relatório de bug, verifique as issues existentes para ver se o
problema já foi reportado. Ao criar um relatório, inclua o máximo de detalhes
possível:

* Um título claro e descritivo
* Os passos exatos para reproduzir o problema
* O comportamento observado após seguir os passos
* O comportamento esperado
* Capturas de tela, se aplicável
* Seu ambiente (distro e versão, Java, Node, navegador, etc.)

> 💡 Este projeto controla rede, firewall e identidade. Ao reportar um bug de
> firewall ou DNS, inclua sempre o `diff` que o Astral produziu entre o estado
> desejado e o observado. É a informação mais valiosa que existe aqui.

### Sugerindo Melhorias

Sugestões são rastreadas como issues do GitHub. Ao criar uma, inclua:

* Um título claro e descritivo
* Uma descrição detalhada da melhoria proposta
* Quaisquer exemplos relevantes
* A motivação para a melhoria

### Pull Requests

1. Faça um fork do repositório e crie sua branch a partir de `main`.
2. Se você adicionou código que deve ser testado, adicione testes.
3. Se você alterou APIs ou contratos, atualize a documentação.
4. Garanta que a suíte de testes passa.
5. Certifique-se de que seu código segue o estilo de código existente.
6. Crie um pull request com título e descrição claros.

## ⚠️ Regras que não são negociáveis

Estas três regras existem por decisões de arquitetura já tomadas. Um PR que as
viola será recusado, mesmo que o código esteja correto.

### 1. O instalador nunca escreve código-fonte

`scripts/install-astral.sh` é o instalador **único**. Ele é bash puro e
**nunca** gera `pom.xml` nem nenhum arquivo `.java`.

> **Por quê:** um instalador que escreve o código que ele mesmo compila
> sobrescreve trabalho com código morto. Código gerado vai ao repositório e
> passa por revisão.

Se o seu PR precisar gerar código, o código gerado entra no repositório.

### 2. `ddl-auto` é `none`, de propósito

Não adicione `@Entity` sem uma migração explícita.

> **Por quê:** o dono do schema do banco `astral` é o módulo `astral-firewall`.
> O plano de controle não roda Flyway porque dois processos migrando o mesmo
> banco competem pela mesma tabela de histórico.

### 3. Não há JWT

A sessão é stateful, no servidor, com cookie `HttpOnly`. Não introduza
autenticação por token sem antes discutir a mudança de modelo — o ATS e o Nginx
dependem do cabeçalho de proxy confiável.

## 🛠 Configuração de Desenvolvimento

### Pré-requisitos

* Java 21 (Oracle JDK ou OpenJDK/Temurin)
* Maven 3.9+
* Node.js 20+
* PostgreSQL 15+
* Python 3.10+ (para os scripts do `fabric/`)

### Build

```bash
# Backend
mvn clean package -DskipTests

# Frontend
cd frontend && npm install && npm run build
```

### Executando Testes

```bash
mvn test
```

### Executando Localmente

```bash
# Infraestrutura
sudo ./installbase.sh

# Aplicação
mvn spring-boot:run
```

### Verificando a instalação

```bash
./scripts/verify-astral.sh
```

## Estrutura do Projeto

```
astral-plataform-hci/
├── src/main/java/com/astral/
│   ├── main/              # plano de controle
│   │   ├── controller/    # Login, Home, SPA forward, firewall proxy, cert download
│   │   ├── security/      # MultiSourceAuthenticationProvider, SecurityConfig,
│   │   │                  # ProxyAuthorizationServer, AstralPrincipal
│   │   └── model/
│   └── tools/             # Installer, InstallerFirewall, InstallerProxy,
│                          # NetworkConfig, Uninstaller
├── src/main/resources/    # application.properties + UI estática legada
├── frontend/              # Vite + React 18 + PrimeReact
├── fabric/
│   ├── DNS/               # Pi-hole
│   ├── firewall/          # módulo Java com 14 entidades JPA
│   ├── network-firewall/  # Python
│   ├── samba-ad-dc/       # Samba AD DC (Arch, Debian 13, Fedora)
│   ├── acess-report-system/  # Flask + PostgreSQL
│   └── frontend/          # UI legada servida pelo proxy
├── scripts/               # instaladores e verificadores bash
├── etc/astral/            # ad.properties
└── installbase.sh         # instalador de dependências do sistema
```

## Padrões de Código

* Siga o estilo de código existente (Spring Boot 3.3.5 / Java 21, Python, React).
* Use nomes de variáveis e métodos significativos.
* Escreva mensagens de commit claras, em português, no formato convencional
  (`tipo(escopo): descrição`).
* Mantenha os métodos pequenos e focados em uma única responsabilidade.
* **Comentário > código morto.** Este projeto tem muitos comentários explicando
  *por que* algo é assim. Preserve esse padrão.

## 📄 Documento Vivo

Mudanças de arquitetura, decisões e rationale de projeto são registradas em
[`projectupdates.md`](../../projectupdates.md). Se o seu PR muda uma decisão,
atualize esse arquivo no mesmo PR.

## Licença

Ao contribuir, você concorda que suas contribuições serão licenciadas sob a
GNU Affero General Public License v3.0 (AGPLv3).

## 🌐 Outros idiomas

| Idioma | Documento |
|--------|-----------|
| 🇧🇷 Português (Brasil) | [CONTRIBUTING.pt-BR.md](CONTRIBUTING.pt-BR.md) |
| 🇺🇸 English | [CONTRIBUTING.en-US.md](CONTRIBUTING.en-US.md) |
| 🇪🇸 Español | [CONTRIBUTING.es-ES.md](CONTRIBUTING.es-ES.md) |
| 🇫🇷 Français | [CONTRIBUTING.fr-FR.md](CONTRIBUTING.fr-FR.md) |