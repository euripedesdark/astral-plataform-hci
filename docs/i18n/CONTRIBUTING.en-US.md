# Contributing to Astral Platform & HCI

Thank you for your interest in contributing to Astral Platform & HCI! This
document provides guidelines for contributing to the project.

## Code of Conduct

This project and all participants are governed by the
[Code of Conduct](../../CODE_OF_CONDUCT.md). By participating, you are expected
to uphold this code.

## How Can I Contribute?

### Reporting Bugs

Before creating a bug report, please check the existing issues as you might find
out that you don't need to create one. When creating a bug report, please
include as many details as possible:

* A clear and descriptive title
* The exact steps which reproduce the problem
* The behavior you observed after following the steps
* The behavior you expected to see instead
* Screenshots, if applicable
* Your environment (distribution and version, Java, Node, browser, etc.)

> 💡 This project controls networking, firewall and identity. When reporting a
> firewall or DNS bug, always include the `diff` Astral produced between desired
> and observed state. It is the most valuable information available here.

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating one,
please include:

* A clear and descriptive title
* A detailed description of the proposed enhancement
* Any relevant examples
* The motivation for the enhancement

### Pull Requests

1. Fork the repository and create your branch from `main`.
2. If you've added code that should be tested, add tests.
3. If you've changed APIs or contracts, update the documentation.
4. Ensure the test suite passes.
5. Make sure your code follows the existing code style.
6. Create a pull request with a clear title and description.

## ⚠️ Non-negotiable rules

These three rules come from architecture decisions already made. A PR that
violates them will be rejected, even if the code is correct.

### 1. The installer never writes source code

`scripts/install-astral.sh` is the **only** installer. It is pure bash and
**never** generates `pom.xml` or any `.java` file.

> **Why:** an installer that writes the code it compiles overwrites working code
> with dead code. Generated code goes into the repository and goes through
> review.

If your PR needs to generate code, the generated code goes into the repository.

### 2. `ddl-auto` is `none`, on purpose

Do not add `@Entity` without an explicit migration.

> **Why:** the owner of the `astral` database schema is the `astral-firewall`
> module. The control plane does not run Flyway because two processes migrating
> the same database compete for the same history table.

### 3. There is no JWT

The session is stateful, server-side, with an `HttpOnly` cookie. Do not
introduce token-based authentication without first discussing the model change —
ATS and Nginx depend on the trusted proxy header.

## 🛠 Development Setup

### Prerequisites

* Java 21 (Oracle JDK or OpenJDK/Temurin)
* Maven 3.9+
* Node.js 20+
* PostgreSQL 15+
* Python 3.10+ (for the `fabric/` scripts)

### Building

```bash
# Backend
mvn clean package -DskipTests

# Frontend
cd frontend && npm install && npm run build
```

### Running Tests

```bash
mvn test
```

### Running Locally

```bash
# Infrastructure
sudo ./installbase.sh

# Application
mvn spring-boot:run
```

### Verifying the installation

```bash
./scripts/verify-astral.sh
```

## Project Structure

```
astral-plataform-hci/
├── src/main/java/com/astral/
│   ├── main/              # control plane
│   │   ├── controller/    # Login, Home, SPA forward, firewall proxy, cert download
│   │   ├── security/      # MultiSourceAuthenticationProvider, SecurityConfig,
│   │   │                  # ProxyAuthorizationServer, AstralPrincipal
│   │   └── model/
│   └── tools/             # Installer, InstallerFirewall, InstallerProxy,
│                          # NetworkConfig, Uninstaller
├── src/main/resources/    # application.properties + legacy static UI
├── frontend/              # Vite + React 18 + PrimeReact
├── fabric/
│   ├── DNS/               # Pi-hole
│   ├── firewall/          # Java module with 14 JPA entities
│   ├── network-firewall/  # Python
│   ├── samba-ad-dc/       # Samba AD DC (Arch, Debian 13, Fedora)
│   ├── acess-report-system/  # Flask + PostgreSQL
│   └── frontend/          # legacy UI served through the proxy
├── scripts/               # bash installers and verifiers
├── etc/astral/            # ad.properties
└── installbase.sh         # system dependency installer
```

## Coding Standards

* Follow the existing code style (Spring Boot 3.3.5 / Java 21, Python, React).
* Use meaningful variable and method names.
* Write clear commit messages, in Portuguese, in the conventional format
  (`type(scope): description`).
* Keep methods small and focused on a single responsibility.
* **Comment over dead code.** This project has many comments explaining *why*
  something is the way it is. Preserve that standard.

## 📄 Living Document

Architecture changes, decisions and project rationale are recorded in
[`projectupdates.md`](../../projectupdates.md). If your PR changes a decision,
update that file in the same PR.

## License

By contributing, you agree that your contributions will be licensed under the
GNU Affero General Public License v3.0 (AGPLv3).

## 🌐 Other languages

| Language | Document |
|----------|----------|
| 🇧🇷 Português (Brasil) | [CONTRIBUTING.pt-BR.md](CONTRIBUTING.pt-BR.md) |
| 🇺🇸 English | [CONTRIBUTING.en-US.md](CONTRIBUTING.en-US.md) |
| 🇪🇸 Español | [CONTRIBUTING.es-ES.md](CONTRIBUTING.es-ES.md) |
| 🇫🇷 Français | [CONTRIBUTING.fr-FR.md](CONTRIBUTING.fr-FR.md) |