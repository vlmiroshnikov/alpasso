# Alpasso - Secret Management Tool

Alpasso is a secret management tool built with Scala 3 (native image). It provides a robust command-line interface for
managing sensitive information with features like hierarchical storage, tagging, and flexible viewing options.

## Features

- **Secure Storage**: Safely store and manage sensitive information with OpenGPG
- **Hierarchical Structure**: Organize secrets in a tree-like structure for better management
- **Native Image Support**: GraalVM native image support for better performance

## Technology Stack

- Scala 3
- cats & cats-effect 
- GraalVM native image

## Building and Running

This is a standard sbt project. You can:

- Compile: `sbt compile`
- Build native image: `sbt alpasso/nativeImage`
