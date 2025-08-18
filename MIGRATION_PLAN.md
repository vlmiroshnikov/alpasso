# Migration Plan for Alpasso Project Structure

## Phase 1: Package Reorganization

### Step 1: Create New Directory Structure

```bash
# Create new package directories
mkdir -p alpasso/src/main/scala/alpasso/application/{cli,commands,presentation}
mkdir -p alpasso/src/main/scala/alpasso/domain/{model,repository,service}
mkdir -p alpasso/src/main/scala/alpasso/infrastructure/{crypto,filesystem,git,persistence}
mkdir -p alpasso/src/main/scala/alpasso/shared/{error,syntax,types}

# Create test directories
mkdir -p alpasso/src/test/scala/alpasso/{application,domain,infrastructure,shared}
```

### Step 2: File Migration Map

#### Application Layer
```
Current Location → New Location
alpasso/cli/CliApp.scala → alpasso/application/cli/CliApp.scala
alpasso/cli/ArgParser.scala → alpasso/application/commands/ArgParser.scala
alpasso/cli/SessionManager.scala → alpasso/application/cli/SessionManager.scala
alpasso/cmdline/Command.scala → alpasso/application/commands/Command.scala
alpasso/cmdline/view/* → alpasso/application/presentation/*
```

#### Domain Layer
```
alpasso/core/model/* → alpasso/domain/model/*
```

#### Infrastructure Layer
```
alpasso/service/cypher/* → alpasso/infrastructure/crypto/*
alpasso/service/fs/* → alpasso/infrastructure/filesystem/*
alpasso/service/git/* → alpasso/infrastructure/git/*
```

#### Shared Layer
```
alpasso/common/* → alpasso/shared/*
```

### Step 3: Update Package Declarations

#### Example Updates:

**Before:**
```scala
package alpasso.cli
import alpasso.cmdline.*
import alpasso.service.cypher.*
```

**After:**
```scala
package alpasso.application.cli
import alpasso.application.commands.*
import alpasso.infrastructure.crypto.*
```

## Phase 2: Interface Extraction

### Step 1: Create Repository Interfaces

Create `alpasso/domain/repository/SecretRepository.scala`:

```scala
package alpasso.domain.repository

import alpasso.domain.model.*
import cats.effect.F

trait SecretRepository[F[_]]:
  def save(secret: Secret): F[Unit]
  def findByName(name: SecretName): F[Option[Secret]]
  def delete(name: SecretName): F[Boolean]
  def listAll(): F[List[Secret]]
```

### Step 2: Create Domain Services

Create `alpasso/domain/service/SecretService.scala`:

```scala
package alpasso.domain.service

import alpasso.domain.model.*
import alpasso.domain.repository.*
import cats.effect.F

trait SecretService[F[_]]:
  def createSecret(name: SecretName, payload: SecretPayload, metadata: Option[SecretMetadata]): F[Secret]
  def updateSecret(name: SecretName, payload: Option[SecretPayload], metadata: Option[SecretMetadata]): F[Secret]
  def deleteSecret(name: SecretName): F[Boolean]
  def findSecrets(filter: SecretFilter): F[List[Secret]]
```

### Step 3: Create Infrastructure Implementations

Create `alpasso/infrastructure/persistence/FileSystemSecretRepository.scala`:

```scala
package alpasso.infrastructure.persistence

import alpasso.domain.repository.*
import alpasso.domain.model.*
import alpasso.infrastructure.crypto.*
import cats.effect.F

class FileSystemSecretRepository[F[_]](
  cryptoService: CryptoService[F],
  fileSystemService: FileSystemService[F]
) extends SecretRepository[F]:
  // Implementation here
```

## Phase 3: Dependency Injection Setup

### Step 1: Create Module Definitions

Create `alpasso/application/Module.scala`:

```scala
package alpasso.application

import alpasso.domain.repository.*
import alpasso.domain.service.*
import alpasso.infrastructure.persistence.*
import alpasso.infrastructure.crypto.*

object Module:
  def createSecretRepository[F[_]](
    cryptoService: CryptoService[F],
    fileSystemService: FileSystemService[F]
  ): SecretRepository[F] =
    new FileSystemSecretRepository(cryptoService, fileSystemService)
  
  def createSecretService[F[_]](
    repository: SecretRepository[F]
  ): SecretService[F] =
    new SecretServiceImpl(repository)
```

### Step 2: Update CLI to Use DI

Update `alpasso/application/cli/CliApp.scala`:

```scala
package alpasso.application.cli

import alpasso.application.Module
import alpasso.domain.service.*
import alpasso.infrastructure.crypto.*

object CliApp extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    for
      cryptoService <- CryptoService.gpg[IO]("fingerprint")
      fileSystemService <- FileSystemService.make[IO]
      repository = Module.createSecretRepository(cryptoService, fileSystemService)
      secretService = Module.createSecretService(repository)
      result <- handleCommands(args, secretService)
    yield result
```

## Phase 4: Error Handling Improvements

### Step 1: Centralized Error Types

Create `alpasso/shared/error/AppError.scala`:

```scala
package alpasso.shared.error

sealed trait AppError extends Throwable:
  def message: String
  def code: String

object AppError:
  case class ValidationError(message: String) extends AppError:
    def code = "VALIDATION_ERROR"
  
  case class NotFoundError(resource: String) extends AppError:
    def message = s"$resource not found"
    def code = "NOT_FOUND"
  
  case class CryptoError(cause: Throwable) extends AppError:
    def message = s"Cryptographic operation failed: ${cause.getMessage}"
    def code = "CRYPTO_ERROR"
```

### Step 2: Error Handling Utilities

Create `alpasso/shared/error/ErrorHandler.scala`:

```scala
package alpasso.shared.error

import cats.effect.F
import cats.syntax.all.*

trait ErrorHandler[F[_]]:
  def handle[A](fa: F[A]): F[Either[AppError, A]]
  def logError(error: AppError): F[Unit]

object ErrorHandler:
  def make[F[_]: Applicative]: ErrorHandler[F] = new ErrorHandler[F]:
    def handle[A](fa: F[A]): F[Either[AppError, A]] = fa.attempt.map(_.leftMap(_.toAppError))
    def logError(error: AppError): F[Unit] = Applicative[F].unit // TODO: implement logging
```

## Phase 5: Configuration Management

### Step 1: Configuration Model

Create `alpasso/shared/config/AppConfig.scala`:

```scala
package alpasso.shared.config

import io.circe.*
import io.circe.generic.semiauto.*

case class AppConfig(
  crypto: CryptoConfig,
  storage: StorageConfig,
  git: GitConfig
)

case class CryptoConfig(
  algorithm: String,
  gpgFingerprint: Option[String]
)

case class StorageConfig(
  basePath: String,
  backupEnabled: Boolean
)

case class GitConfig(
  autoCommit: Boolean,
  remoteSync: Boolean
)

object AppConfig:
  given Encoder[AppConfig] = deriveEncoder
  given Decoder[AppConfig] = deriveDecoder
```

### Step 2: Configuration Loading

Create `alpasso/shared/config/ConfigLoader.scala`:

```scala
package alpasso.shared.config

import cats.effect.*
import io.circe.parser.*
import java.nio.file.*

trait ConfigLoader[F[_]]:
  def load(): F[AppConfig]

object ConfigLoader:
  def make[F[_]: Sync]: ConfigLoader[F] = new ConfigLoader[F]:
    def load(): F[AppConfig] =
      for
        configPath <- Sync[F].delay(Path.of("config", "application.json"))
        content <- Sync[F].blocking(Files.readString(configPath))
        config <- Sync[F].fromEither(decode[AppConfig](content))
      yield config
```

## Phase 6: Testing Improvements

### Step 1: Test Utilities

Create `alpasso/src/test/scala/alpasso/shared/TestUtils.scala`:

```scala
package alpasso.shared

import cats.effect.*
import cats.syntax.all.*
import java.nio.file.*

object TestUtils:
  def createTempDirectory[F[_]: Sync]: F[Path] =
    Sync[F].blocking(Files.createTempDirectory("alpasso-test"))
  
  def cleanupDirectory[F[_]: Sync](path: Path): F[Unit] =
    Sync[F].blocking {
      if Files.exists(path) then
        Files.walk(path)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(Files.delete)
    }
```

### Step 2: Test Structure

```
alpasso/src/test/scala/alpasso/
├── application/
│   ├── cli/
│   │   └── CliAppSpec.scala
│   ├── commands/
│   │   └── CommandSpec.scala
│   └── presentation/
│       └── ViewSpec.scala
├── domain/
│   ├── model/
│   │   └── SecretSpec.scala
│   └── service/
│       └── SecretServiceSpec.scala
├── infrastructure/
│   ├── crypto/
│   │   └── CryptoServiceSpec.scala
│   └── persistence/
│       └── FileSystemRepositorySpec.scala
└── shared/
    └── TestUtils.scala
```

## Implementation Timeline

### Week 1: Foundation
- [ ] Create new directory structure
- [ ] Move files to new locations
- [ ] Update package declarations
- [ ] Fix compilation errors

### Week 2: Interfaces and Services
- [ ] Extract repository interfaces
- [ ] Create domain services
- [ ] Implement infrastructure layer
- [ ] Set up dependency injection

### Week 3: Error Handling and Configuration
- [ ] Implement centralized error handling
- [ ] Add configuration management
- [ ] Update error handling throughout the codebase

### Week 4: Testing and Documentation
- [ ] Add comprehensive unit tests
- [ ] Create integration tests
- [ ] Update documentation
- [ ] Performance testing

## Rollback Plan

If issues arise during migration:

1. **Git Branches**: Create feature branches for each phase
2. **Incremental Migration**: Move files in small batches
3. **Continuous Testing**: Run tests after each change
4. **Backup**: Keep original structure in a separate branch

## Success Criteria

- [ ] All tests pass
- [ ] No compilation errors
- [ ] Performance is maintained or improved
- [ ] Code is more maintainable and testable
- [ ] Documentation is updated
- [ ] Team can work on different layers independently 