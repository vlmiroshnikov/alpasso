# AGENTS.md

## Build/Lint/Test Commands
- Build: `sbt compile`
- Test all: `sbt test`
- Test single: `sbt "testOnly *SuiteName"` (e.g., `sbt "testOnly alpasso.cmdline.ArgParserSuite"`)
- Format code: `sbt scalafmt`
- Check format: `sbt scalafmtCheck`
- Build native image: `sbt alpasso/nativeImage`

## Code Style Guidelines

### Language & Frameworks
- **Language**: Scala 3 with functional programming paradigm
- **Core libraries**: cats, cats-effect, tofu (glass-core, tofu-core-higher-kind)
- **CLI parsing**: decline
- **Serialization**: circe
- **Testing**: munit, weaver-cats
- **Native compilation**: GraalVM native image

### Imports
- Import grouping is automatically handled by scalafmt
- Grouped by: java.*, scala.*, cats.*, tofu.*, alpasso.* (ASCII sorted)
- Use wildcard imports sparingly; prefer explicit imports for type clarity
- Import syntax from cats: `import cats.syntax.all.*`

### Naming Conventions
- **Methods/Variables**: camelCase (e.g., `createSecret`, `repoDir`)
- **Types/Classes/Traits/Objects**: PascalCase (e.g., `SecretName`, `RepositoryReader`)
- **Type parameters**: Single uppercase letters (e.g., `F[_]`, `A`, `B`)
- **Opaque type companions**: Same name as the opaque type (e.g., `object SecretName`)

### Types & Domain Models
- Use **opaque types** for domain models to prevent type confusion:
  ```scala
  opaque type SecretName = Path
  opaque type SecretMetadata = Map[String, String]
  opaque type Recipient <: String = String
  ```
- Define companion objects with extension methods and smart constructors:
  ```scala
  object SecretName:
    extension (s: SecretName)
      def shortName: String = s.getFileName.toString
      def asPath: Path = s
    def of(name: String): ValidatedNel[String, SecretName] = ...
  ```
- Use **strong typing** throughout - avoid String for domain concepts

### Error Handling
- Use `Result[T] = Either[Err, T]` for operations that can fail
- Use `ValidatedNel[String, T]` for input validation
- Use `EitherT[F, E, A]` to lift `F[Either[E, A]]` into effectful computations
- Define error enums with cases:
  ```scala
  enum Err:
    case SecretRepoErr(err: RepositoryErr)
    case StorageNotInitialized(path: Path)
    case InternalErr
    case CommandSyntaxError(help: String)
  ```
- Use **Upcast** pattern from glass-core for error lifting:
  ```scala
  given Upcast[Err, RepositoryErr] = Err.SecretRepoErr(_)
  ```
- Use `.liftE[E]` syntax to convert errors via Upcast

### Functional Programming Patterns
- Use **IOApp** for entry points: `object AlpassoApp extends IOApp`
- Use **cats-effect IO** for side effects
- Use **OptionT** and **EitherT** for working with Option/Either in effect contexts
- Use **State monad** for stateful operations: `StateF[F, *]`
- Use **Functor/Show/Encoder/Decoder** instances via `given`
- Prefer extension methods over implicit classes

### Code Organization
- **Domain layer**: `alpasso.domain.*` - opaque types, domain models
- **Infrastructure layer**: `alpasso.infrastructure.*` - filesystem, git, cypher
- **Command layer**: `alpasso.commands.*` - business logic orchestration
- **Presentation layer**: `alpasso.presentation.*` - view models, Show instances
- **Shared models**: `alpasso.shared.models.*` - Result type, type aliases
- **Shared errors**: `alpasso.shared.errors.*` - error definitions
- **Command line**: `alpasso.cmdline.*` - argument parsing

### Type Classes & Given Instances
- Use `given` keyword for type class instances
- Provide Show instances for display: `given Show[SecretName] = Show.show(_.shortName)`
- Provide Encoder/Decoder for circe serialization
- Use extension methods on opaque types in companion objects

### Testing
- Use **munit.FunSuite** for test suites
- Test file naming: `*Suite.scala` (e.g., `SecretNameSuite.scala`)
- Write descriptive test names: `test("SecretName.of should validate empty names")`
- Use `assert()`, `assertEquals()`, `assertClauses()` for assertions
- Test validation results: `assert(SecretName.of("").isInvalid)`

### Command Pattern
- Define traits for command algebras:
  ```scala
  trait Command[F[_]]:
    def create(name: SecretName, payload: SecretPayload, meta: Option[SecretMetadata]): F[Result[SecretView]]
    def remove(name: SecretName): F[Result[SecretView]]
  ```
- Implement in companion object with private class:
  ```scala
  object Command:
    def make[F[_]: {Sync, Console}](config: RepositoryConfiguration): Command[F] = ...
    private class Impl[F[_]: ...] extends Command[F]: ...
  ```

### Compiler Options
- -deprecation, -new-syntax, -Xkind-projector, -experimental
- Additional: -Wconf:msg=eta-expanded even though:silent

### Formatting (scalafmt)
- Indent: 2 spaces (main), 4 spaces (defnSite)
- Max column width: 100
- Align arrows and tokens: true
- Config style arguments for 5+ params
- Redundant braces removal enabled
- Import sorting: ASCII, grouped by library prefix
