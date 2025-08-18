#!/bin/bash

# Alpasso Project Structure Migration Script
# This script helps migrate from the current structure to the new clean architecture

set -e

echo "Starting Alpasso project structure migration..."

# Create new directory structure
echo "Creating new directory structure..."

mkdir -p alpasso/src/main/scala/alpasso/application/cli
mkdir -p alpasso/src/main/scala/alpasso/application/commands
mkdir -p alpasso/src/main/scala/alpasso/application/presentation

mkdir -p alpasso/src/main/scala/alpasso/domain/model
mkdir -p alpasso/src/main/scala/alpasso/domain/repository
mkdir -p alpasso/src/main/scala/alpasso/domain/service

mkdir -p alpasso/src/main/scala/alpasso/infrastructure/crypto
mkdir -p alpasso/src/main/scala/alpasso/infrastructure/filesystem
mkdir -p alpasso/src/main/scala/alpasso/infrastructure/git
mkdir -p alpasso/src/main/scala/alpasso/infrastructure/persistence

mkdir -p alpasso/src/main/scala/alpasso/shared/error
mkdir -p alpasso/src/main/scala/alpasso/shared/syntax
mkdir -p alpasso/src/main/scala/alpasso/shared/types
mkdir -p alpasso/src/main/scala/alpasso/shared/config

# Create test directories
mkdir -p alpasso/src/test/scala/alpasso/application/cli
mkdir -p alpasso/src/test/scala/alpasso/application/commands
mkdir -p alpasso/src/test/scala/alpasso/application/presentation

mkdir -p alpasso/src/test/scala/alpasso/domain/model
mkdir -p alpasso/src/test/scala/alpasso/domain/service

mkdir -p alpasso/src/test/scala/alpasso/infrastructure/crypto
mkdir -p alpasso/src/test/scala/alpasso/infrastructure/filesystem
mkdir -p alpasso/src/test/scala/alpasso/infrastructure/git
mkdir -p alpasso/src/test/scala/alpasso/infrastructure/persistence

mkdir -p alpasso/src/test/scala/alpasso/shared

# Create additional directories
mkdir -p docs
mkdir -p scripts
mkdir -p config

echo "Directory structure created successfully!"

# Move files (commented out for safety - uncomment when ready)
echo "Ready to move files. Uncomment the move commands in this script when ready."

# Application Layer
# mv alpasso/src/main/scala/alpasso/cli/CliApp.scala alpasso/src/main/scala/alpasso/application/cli/
# mv alpasso/src/main/scala/alpasso/cli/SessionManager.scala alpasso/src/main/scala/alpasso/application/cli/
# mv alpasso/src/main/scala/alpasso/cli/ArgParser.scala alpasso/src/main/scala/alpasso/application/commands/
# mv alpasso/src/main/scala/alpasso/cmdline/Command.scala alpasso/src/main/scala/alpasso/application/commands/
# mv alpasso/src/main/scala/alpasso/cmdline/view/* alpasso/src/main/scala/alpasso/application/presentation/

# Domain Layer
# mv alpasso/src/main/scala/alpasso/core/model/* alpasso/src/main/scala/alpasso/domain/model/

# Infrastructure Layer
# mv alpasso/src/main/scala/alpasso/service/cypher/* alpasso/src/main/scala/alpasso/infrastructure/crypto/
# mv alpasso/src/main/scala/alpasso/service/fs/* alpasso/src/main/scala/alpasso/infrastructure/filesystem/
# mv alpasso/src/main/scala/alpasso/service/git/* alpasso/src/main/scala/alpasso/infrastructure/git/

# Shared Layer
# mv alpasso/src/main/scala/alpasso/common/* alpasso/src/main/scala/alpasso/shared/

echo "Migration script completed!"
echo ""
echo "Next steps:"
echo "1. Uncomment the move commands in this script"
echo "2. Run the script again to move files"
echo "3. Update package declarations in all moved files"
echo "4. Update import statements"
echo "5. Run 'sbt compile' to check for compilation errors"
echo "6. Follow the detailed migration plan in MIGRATION_PLAN.md" 