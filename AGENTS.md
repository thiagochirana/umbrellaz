# AGENTS.md

## Purpose

This file defines the mandatory operating rules for any coding agent working on the Umbrellaz repository.

Umbrellaz is a modular, server-authoritative Minecraft Fabric mod with common/server code and a client GUI surface, focused initially on:

- player authorization
- whitelist management
- SQLite persistence
- administrative commands
- blocking unauthorized players from interacting with the server

The project is expected to grow over time with additional modules such as:

- roles
- permissions
- bans
- mutes
- homes
- teleportation
- spawn management
- economy
- audit logs
- backups

The codebase must remain modular as these features are introduced.

---

# Mandatory Documentation Reading

Before creating, editing, moving, deleting, or refactoring application code, the agent MUST read:

1. `docs/DEVELOPMENT.md`
2. `docs/ARCHITECTURE.md`

These files are mandatory context for implementation.

Do not generate implementation code before reading both documents.

If a task involves:

- architecture
- module boundaries
- database
- threading
- lifecycle
- events
- commands
- authorization
- persistence
- cache
- dependency direction

the agent MUST verify the relevant rules in both documents before making changes.

If the current code conflicts with the documentation, the documentation is considered the intended architecture unless the user explicitly requests otherwise.

If an architectural rule needs to change, update the appropriate documentation as part of the same change.

---

# Instruction Priority

When instructions conflict, use this priority:

1. explicit user request
2. `AGENTS.md`
3. `docs/ARCHITECTURE.md`
4. `docs/DEVELOPMENT.md`
5. existing implementation

Do not preserve an existing pattern only because it already exists if it violates the documented architecture.

---

# Project Baseline

Assume the following unless explicitly changed:

- Minecraft Java 26.3
- Fabric
- Java 25
- Gradle Kotlin DSL
- SQLite
- SQLite JDBC
- JUnit
- SLF4J
- universal client/server mod JAR with server-authoritative behavior

Do not change foundational versions without a clear reason.

Do not guess dependency versions.

When modifying Fabric, Loom, Minecraft, Java or other major dependencies, verify compatibility first.

---

# Development Workflow

Before implementing a task:

1. read `docs/DEVELOPMENT.md`
2. read `docs/ARCHITECTURE.md`
3. inspect the relevant existing module
4. identify which layer owns the change
5. implement the smallest coherent change
6. run tests
7. run the Gradle build
8. fix relevant warnings or failures
9. confirm the architecture was preserved

Preferred commands:

```bash
./gradlew test
./gradlew build
```

When appropriate:

```bash
./gradlew runServer
```

Do not consider a task complete while the project does not compile.

---

# Architecture Rules

The project follows a feature-first modular architecture.

Prefer:

```text
auth/
whitelist/
player/
roles/
permissions/
```

over global layer folders such as:

```text
controllers/
services/
repositories/
models/
```

Infrastructure shared by modules belongs under:

```text
infra/
```

Examples:

```text
infra/db/
infra/config/
```

Commands and Fabric events are adapters.

Business logic belongs in services/use cases.

Persistence belongs in repositories.

SQLite access belongs behind the database infrastructure.

---

# Dependency Direction

The expected direction is:

```text
Minecraft/Fabric
      ↓
Command/Event
      ↓
Service / Use Case
      ↓
Repository
      ↓
DatabaseExecutor
      ↓
SQLite
```

Do not reverse this dependency flow.

Repositories must not know about Fabric command sources, Minecraft players or Minecraft server objects.

Domain and application logic should depend on simple Java types whenever possible.

---

# Commands

Commands must remain thin.

Commands may:

- parse arguments
- validate command syntax
- call a service/use case
- translate the result into a Minecraft message

Commands must not:

- execute SQL
- contain significant business rules
- directly access SQLite
- directly implement authorization rules
- manage database transactions
- contain large workflows

Prefer:

```text
Command
  ↓
Service
```

instead of:

```text
Command
  ↓
Repository
```

---

# Event Handlers

Fabric/Minecraft event handlers must also remain thin.

Events should:

- translate a Minecraft event into an application operation
- call the relevant service
- apply the result to Minecraft when appropriate

They should not contain persistence or complex business rules.

---

# Database Rules

SQLite is the persistent database.

The database file should live under the mod configuration directory, for example:

```text
config/umbrellaz/umbrellaz.db
```

All blocking SQLite work must execute outside the Minecraft server thread.

Use the project's dedicated database executor.

Never query SQLite from:

- movement handlers
- tick handlers
- combat checks
- block interaction checks
- inventory checks
- permission checks on hot paths

Hot-path state must be kept in memory.

---

# Threading Rules

The Minecraft server thread must never be blocked by JDBC operations.

Database work:

```text
Minecraft thread
      ↓
DatabaseExecutor
      ↓
SQLite
```

When the async operation completes and Minecraft state must be changed:

```text
SQLite
   ↓
CompletableFuture
   ↓
server.execute(...)
   ↓
Minecraft server thread
```

Never mutate Minecraft state from the database thread.

Do not use blocking calls such as:

```text
Future.get()
CompletableFuture.join()
Thread.sleep()
```

on the Minecraft server thread.

---

# Authorization Rules

Authorization is server authoritative.

Never trust:

- client-provided role information
- client-provided permission information
- client claims that authorization already happened

The authenticated Minecraft session UUID is the primary player identity.

Usernames are mutable metadata and must not be used as permanent identifiers.

When authorization state is unknown:

```text
fail closed
```

The player remains blocked until authorization is positively established.

---

# Auth State

Connected player authorization state must be represented in memory.

Expected concept:

```text
Map<UUID, AuthSession>
```

Possible states initially:

```text
BLOCKED
AUTHENTICATED
```

Do not query SQLite repeatedly to determine whether a connected player may interact with the server.

---

# Whitelist

Umbrellaz maintains its own whitelist in SQLite.

Do not use Minecraft's native `whitelist.json` as the primary data source.

The whitelist module must remain independent from the authentication module.

Conceptually:

```text
Whitelist
    ↓
determines eligibility

Auth
    ↓
determines current session state
```

Today, being whitelisted may automatically authenticate a player.

The architecture must not assume that this will always be true.

---

# Player Identity

Always identify players persistently using UUID.

Prefer:

```text
UUID
```

Never use username as the database primary key.

Username may be stored for display and lookup purposes.

---

# Repositories

Repositories are persistence adapters.

Repositories may:

- execute SQL
- map SQL rows
- insert/update/delete records
- perform persistence-level queries

Repositories must not:

- send Minecraft messages
- teleport players
- inspect Minecraft permissions
- execute commands
- contain Fabric event logic

---

# Services / Use Cases

Services contain application behavior.

Examples:

```text
WhitelistService
AuthService
PlayerService
```

Services coordinate:

- repositories
- cache
- domain state
- application rules

Do not split every method into its own class unless there is a clear benefit.

Prefer cohesive services over abstraction for abstraction's sake.

---

# Infrastructure

Shared technical components belong under `infra`.

Examples:

```text
infra/db/
infra/config/
```

Database infrastructure should include concepts such as:

```text
Database
DatabaseExecutor
MigrationRunner
SQLiteConnectionFactory
```

Do not place domain behavior inside infrastructure packages.

---

# Composition

Do not use dependency injection frameworks.

Use explicit construction.

The common `Umbrellaz.java` initializer is registration-only. It may install
global callbacks and lifecycle hooks, but it must not construct configuration,
database, executor, repository, service, cache, command, or other runtime
state.

A per-server runtime composition root/factory creates, wires, and closes those
components for each dedicated or integrated server lifecycle. The common
initializer must not own or retain that server runtime, and it must not
accumulate application business logic.

---

# Logging

Use SLF4J.

Do not use:

```java
System.out.println(...)
```

Log meaningful events such as:

- mod startup
- database initialization
- migration execution
- migration failure
- administrative whitelist changes
- authorization failures
- shutdown failures

Do not spam logs from tick or movement handlers.

---

# Error Handling

Database exceptions must not silently kill asynchronous execution.

Every asynchronous pipeline must handle failures.

Authorization failures should default to:

```text
BLOCKED
```

Do not allow a player because an authorization lookup failed.

---

# Tests

Business logic should be testable without booting Minecraft whenever reasonably possible.

Prioritize unit tests for:

- whitelist rules
- authentication state transitions
- cache behavior
- repository behavior where useful

Avoid excessive mocks.

Prefer simple fake implementations for domain/application tests where appropriate.

---

# Code Style

Use clear, explicit Java.

Prefer:

- small cohesive classes
- descriptive names
- immutable data where practical
- constructor injection
- explicit dependency ownership
- early returns
- simple control flow

Avoid:

- god classes
- hidden global state
- static service containers
- service locator patterns
- unnecessary inheritance
- generic abstractions before they are needed
- reflection-based application architecture

Do not add comments to generated code unless explicitly requested by the user.

Code should be understandable through naming and structure.

---

# Scope Control

Do not implement unrelated future functionality.

If the task is whitelist-related, do not also create:

- roles
- economy
- homes
- permissions
- GUI
- HTTP API

unless explicitly requested.

Design for extension, but implement only the required behavior.

---

# Documentation Maintenance

When adding an architectural concept that affects future development, update:

```text
docs/ARCHITECTURE.md
```

When introducing a new development rule or convention, update:

```text
docs/DEVELOPMENT.md
```

`AGENTS.md` should remain concise and focused on how agents must operate.

---

# Final Checklist

Before completing any implementation task verify:

- `docs/DEVELOPMENT.md` was read
- `docs/ARCHITECTURE.md` was read
- feature boundaries were respected
- no JDBC operation runs on the Minecraft thread
- no Minecraft state is modified from the database thread
- commands remain thin
- repositories contain no Minecraft logic
- UUID is used for player identity
- authorization fails closed
- build passes
- tests pass
- documentation was updated if architecture changed
