# Alpasso Project Structure Improvements

## Current Issues

1. **Mixed Responsibilities**: CLI and command logic are somewhat intertwined
2. **Flat Service Structure**: All services are at the same level without clear domain boundaries
3. **Missing Infrastructure Layer**: No clear separation between business logic and external concerns
4. **Inconsistent Naming**: Some packages use abbreviations (`cli`, `cmdline`) while others are descriptive
5. **Limited Test Organization**: Tests are minimal and not well organized

## Recommended New Structure

```
alpasso/
├── src/
│   ├── main/
│   │   └── scala/
│   │       └── alpasso/
│   │           ├── application/           # Application layer (CLI, commands)
│   │           │   ├── cli/               # CLI entry point and session management
│   │           │   ├── commands/          # Command definitions and parsing
│   │           │   └── presentation/      # View models and output formatting
│   │           ├── domain/                # Domain layer (core business logic)
│   │           │   ├── model/             # Domain entities and value objects
│   │           │   ├── repository/        # Repository interfaces
│   │           │   └── service/           # Domain services
│   │           ├── infrastructure/        # Infrastructure layer
│   │           │   ├── crypto/            # Cryptographic operations
│   │           │   ├── filesystem/        # File system operations
│   │           │   ├── git/               # Git operations
│   │           │   └── persistence/       # Data persistence implementations
│   │           └── shared/                # Shared utilities and common code
│   │               ├── error/             # Error handling
│   │               ├── syntax/            # Extension methods
│   │               └── types/             # Common type definitions
│   └── test/
│       └── scala/
│           └── alpasso/
│               ├── application/           # Application layer tests
│               ├── domain/                # Domain layer tests
│               ├── infrastructure/        # Infrastructure layer tests
│               └── shared/                # Shared test utilities
├── docs/                                 # Documentation
├── scripts/                              # Build and deployment scripts
└── config/                               # Configuration files
```

## Detailed Package Responsibilities

### 1. Application Layer (`application/`)

**Purpose**: Handle user interactions and coordinate between layers

- **`cli/`**: Entry point, session management, argument parsing
- **`commands/`**: Command definitions, validation, and orchestration
- **`presentation/`**: View models, output formatting, user interface concerns

### 2. Domain Layer (`domain/`)

**Purpose**: Core business logic and domain rules

- **`model/`**: Domain entities, value objects, and business rules
- **`repository/`**: Repository interfaces (contracts)
- **`service/`**: Domain services that implement business logic

### 3. Infrastructure Layer (`infrastructure/`)

**Purpose**: External concerns and technical implementations

- **`crypto/`**: Cryptographic operations (GPG, encryption/decryption)
- **`filesystem/`**: File system operations and storage
- **`git/`**: Git repository operations
- **`persistence/`**: Repository implementations and data access

### 4. Shared Layer (`shared/`)

**Purpose**: Cross-cutting concerns and utilities

- **`error/`**: Error types and error handling utilities
- **`syntax/`**: Extension methods and implicit classes
- **`types/`**: Common type definitions and aliases

## Migration Strategy

### Phase 1: Create New Structure
1. Create new package directories
2. Move files to new locations
3. Update import statements
4. Ensure compilation works

### Phase 2: Refactor and Improve
1. Extract interfaces where appropriate
2. Improve error handling
3. Add proper dependency injection
4. Enhance test coverage

### Phase 3: Documentation and Polish
1. Add comprehensive documentation
2. Create architecture decision records (ADRs)
3. Add integration tests
4. Performance optimization

## Benefits of New Structure

1. **Clear Separation of Concerns**: Each layer has a specific responsibility
2. **Better Testability**: Easier to unit test each layer independently
3. **Improved Maintainability**: Changes in one layer don't affect others
4. **Scalability**: Easy to add new features without affecting existing code
5. **Team Collaboration**: Multiple developers can work on different layers
6. **Dependency Management**: Clear dependency flow from application to infrastructure

## Additional Recommendations

### 1. Add Configuration Management
- Create a dedicated configuration module
- Support for different environments (dev, test, prod)
- External configuration files

### 2. Improve Error Handling
- Centralized error types
- Proper error recovery strategies
- User-friendly error messages

### 3. Add Logging and Monitoring
- Structured logging
- Performance metrics
- Health checks

### 4. Enhance Testing
- Unit tests for each layer
- Integration tests
- Property-based testing
- Test utilities and fixtures

### 5. Documentation
- API documentation
- Architecture documentation
- User guides
- Development setup guide

## Implementation Priority

1. **High Priority**: Reorganize packages and update imports
2. **Medium Priority**: Extract interfaces and improve error handling
3. **Low Priority**: Add comprehensive testing and documentation

This structure follows clean architecture principles and will make the codebase more maintainable, testable, and scalable. 