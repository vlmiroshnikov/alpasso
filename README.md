# Alpasso

A secure secret management tool built with Scala 3 and compiled to a native image via GraalVM. Alpasso provides a hierarchical storage system for managing sensitive information with OpenGPG encryption, tagging support, and flexible viewing options.

## Features

- **Secure Storage**: Encrypt secrets using OpenGPG
- **Hierarchical Structure**: Organize secrets in a tree-like structure
- **Tagging System**: Attach metadata to secrets for easy filtering
- **Multiple Repositories**: Manage multiple secret repositories and switch between them
- **Git Integration**: Built-in version control with commit history
- **Remote Sync**: Sync repositories with remote git repositories
- **Native Image**: Fast startup with GraalVM native image compilation
- **Flexible Output**: Display secrets in tree or table format

## Technology Stack

- **Language**: Scala 3
- **Functional Programming**: cats, cats-effect, tofu
- **CLI Parsing**: decline
- **Serialization**: circe
- **Encryption**: OpenGPG 
- **Version Control**: JGit
- **Native Compilation**: GraalVM native-image
- **Testing**: munit, weaver-cats

## Installation

### From Source

```bash
git clone <repository-url>
cd alpasso
sbt alpasso/nativeImage
```

The native binary will be available at `alpasso/target/native-image/alpasso`.

## Quick Start

### Initialize a Repository

```bash
alpasso repo init -p ~/.secrets --gpg-fingerprint YOUR_GPG_FINGERPRINT
```

### Add a Secret

```bash
# Add a secret with name and value
alpasso new api-key my-secret-value

# Add a secret with metadata
alpasso new database-url "postgres://localhost/db" --meta env=prod,team=backend
```

### List Secrets

```bash
# List all secrets in tree format
alpasso ls

# List secrets in table format
alpasso ls --output Table

# Filter secrets by pattern
alpasso ls --grep api
```

### Show Sensitive Data

By default, secret values are masked. Use `--unmasked` to display them:

```bash
alpasso ls --unmasked
```

### Update a Secret

```bash
alpasso patch api-key new-value

# Update metadata only
alpasso patch api-key --meta env=staging
```

### Remove a Secret

```bash
alpasso rm api-key
```

### Manage Multiple Repositories

```bash
# List all repositories
alpasso repo list

# Switch to a different repository
alpasso repo switch 1
```

### View History

```bash
alpasso repo log
```

### Remote Sync

```bash
# Setup remote repository
alpasso repo remote setup https://github.com/user/secrets.git

# Sync with remote
alpasso repo remote sync
```

See LICENSE file for details.
