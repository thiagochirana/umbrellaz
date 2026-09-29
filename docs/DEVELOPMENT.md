# Umbrellaz Development Guide

## Purpose

This document defines how code should be written in Umbrellaz.

For system structure and dependency boundaries, see:

```text
docs/ARCHITECTURE.md
```

The objective is to maintain a codebase that remains understandable as the mod grows.

Umbrellaz should behave like a modular Java application embedded in Fabric rather than a collection of Minecraft event handlers.

---

# Technology

Current baseline:

```text
Minecraft: 26.3
Platform: Fabric
Java: 25
Build: Gradle Kotlin DSL
Database: SQLite
Database access: JDBC
Tests: JUnit
Logging: SLF4J
```

Umbrellaz is delivered as one universal Fabric mod JAR containing common/server code and a split client source set. The server remains authoritative; the client mod is required for the custom GUI migration. The exact Minecraft and Fabric versions must match the release metadata.

The universal artifact is intended for both dedicated and integrated servers. The same `umbrellaz-mod-<version>.jar` is installed in the client and server `mods` directories; it includes the lock item model and texture, so no separate resource pack, URL, SHA-1, or required-resource-pack configuration is needed. TLauncher users must install the matching universal JAR as the client mod. Release and installation documentation must identify the universal JAR, exact compatibility, and the assumption that the player has an authenticated server session.

The Minecraft 26.3 lane uses Fabric Loom `net.fabricmc.fabric-loom` 1.17.21 with the non-obfuscated runtime namespace. It does not use `officialMojangMappings()` or require a remapping task; the normal Gradle `jar` is the production runtime artifact. Split source sets and `loom.mods` remain valid.

The common initializer must be safe to load in either environment and perform registration only. A per-server runtime composition root/factory constructs and closes server-owned databases, executors, repositories, services, caches, command modules, and protocol/runtime state for each dedicated or integrated server lifecycle; this includes stopping and recreating them across an integrated-server restart. Client rendering and input are never loaded from common/server code.

---

# Development Principles

Prioritize:

- simplicity
- cohesion
- explicit dependencies
- feature isolation
- predictable threading
- low coupling
- testability
- maintainability

Avoid speculative abstraction.

Do not create an interface merely because a class exists.

Create abstractions where they provide one or more of:

- architectural boundaries
- replaceable implementation
- easier testing
- meaningful separation of responsibility

---

# Feature-First Organization

The primary organizational unit is the feature.

Prefer:

```text
auth/
whitelist/
player/
```

Each module contains its own relevant application components.

Example:

```text
whitelist/
├── WhitelistEntry.java
├── WhitelistRepository.java
├── WhitelistService.java
├── WhitelistCommand.java
└── WhitelistEvents.java
```

Do not create global directories like:

```text
services/
repositories/
commands/
models/
```

unless they contain genuinely shared abstractions.

---

# Naming

Use names that describe behavior and responsibility.

Good:

```text
WhitelistService
WhitelistRepository
AuthSession
DatabaseExecutor
MigrationRunner
AuthorizationService
```

Avoid vague names:

```text
Manager
Helper
Utils
Handler
Processor
Common
Misc
```

A name such as `Handler` is acceptable only when the responsibility is specific and obvious.

---

# Packages

Target package:

```text
dev.chirana.umbrellaz
```

Expected structure:

```text
dev.chirana.umbrellaz
├── auth
├── whitelist
├── player
├── command
├── config
└── infra
```

Infrastructure:

```text
dev.chirana.umbrellaz.infra.db
dev.chirana.umbrellaz.infra.db.sqlite
```

---

# Java Style

Use Java 25 features where they improve clarity.

Suitable examples include:

- records for immutable data carriers
- switch expressions
- pattern matching where useful

Do not use newer language features simply to make code shorter.

Favor readability.

---

# Constructors

Dependencies should normally be explicit constructor arguments.

Prefer:

```java
public WhitelistService(
    WhitelistRepository repository,
    DatabaseExecutor databaseExecutor,
    WhitelistCache cache
) {
    this.repository = repository;
    this.databaseExecutor = databaseExecutor;
    this.cache = cache;
}
```

Avoid hidden global dependencies.

Do not introduce a DI framework.

---

# Composition Root

The common main initializer performs global registration and lifecycle hooks
only. It must not wire or construct a database, executor, repository, service,
cache, command module, or other server runtime state.

A per-server runtime composition root/factory creates, wires, and closes those
components for each dedicated or integrated server lifecycle:

```text
Database
   ↓
Repository
   ↓
Service
   ↓
Command/Event
```

Classes should receive dependencies rather than discover them globally. The
common initializer must not own or retain the per-server runtime.

---

# Universal Mod and Environment Boundaries

The project produces one universal Fabric mod JAR. Common/server code
lives in `src/main/java`; client-only rendering and input live in
`src/client/java` and use a separate client entrypoint. `src/main/java` must not
import or load `net.minecraft.client` classes. Shared protocol contracts must
remain neutral so they can be loaded by both environments.

The common initializer is environment-safe and registration-only. It must not
construct server-owned databases, executors, repositories, services, caches,
command modules, or other runtime state. Those objects are created for each
server lifecycle, owned by that
server instance, and closed during shutdown before a later lifecycle creates a
new instance. This applies equally to integrated-server stop/restart; no stale
runtime or server reference may survive the restart.

Fabric callbacks and payload registrations are global and are installed exactly
once. They must not register `LockEvents` or capture server-owned services per
server lifecycle. At invocation, each callback resolves the runtime associated
with the event's `MinecraftServer` or connection. If no matching ready runtime
exists, it fails closed. Shutdown first marks and removes the runtime
association, then closes that instance, so an integrated-server restart cannot
reuse stale services or callback captures.

The matching client mod is required for the custom GUI. A client that is absent,
times out during the bounded handshake, or is incompatible is disconnected;
there is no vanilla GUI fallback. Installation instructions must name the
universal artifact, exact Minecraft/Fabric compatibility, TLauncher client
installation, and the authenticated-session assumption. The lock item model
and texture are bundled in the universal JAR used by both client and server.

---

# Custom GUI and Protocol Development

Networking uses typed `CustomPacketPayload` contracts with `StreamCodec` and
bounded fields. The application handshake is explicit and versioned; Fabric
registration alone is not a capability negotiation. Each connection tracks a
capability state:

```text
UNNEGOTIATED → COMPATIBLE
UNNEGOTIATED → INCOMPATIBLE → DISCONNECTED
UNNEGOTIATED → DISCONNECTED
```

The handshake has a bounded deadline. Protocol sessions use a connection-scoped
nonce. Action contexts are one-shot, expire promptly, and are consumed only
after server validation, preventing replay. `UNNEGOTIATED` is fail-closed during
the handshake window: even an otherwise authenticated/whitelisted player stays
interaction-blocked until the protocol reaches `COMPATIBLE`. Timeout,
incompatible response, disconnect, and handshake failure cancel the deadline and
session, then disconnect on the server thread; no blocking wait is permitted.

Before any KDF, database, reservation, or world work, an action context token
must atomically transition `OPEN → IN_FLIGHT`; only the winner dispatches. Its
completion or cancellation is terminal and idempotent. Each context binds to
the physical connection/session, player UUID, negotiated nonce/generation,
server-selected action and target generations, and an expiry. Disconnect and
runtime shutdown invalidate contexts; retries receive fresh tokens. A client
capability is not authorization: every action is checked against the
authenticated server session, feature capability, bounds, nonce/context/token
state, and application authorization on the server.

The client owns only rendering, focus, transient UI state, and request input.
The server owns identity, authentication, locks, target resolution, action
tokens, KDF work, persistence, item accounting, world mutation, and final
results. JDBC remains behind `DatabaseExecutor`; asynchronous completion must
return to the server thread before changing Minecraft state.

Custom `Screen` implementations and bundled item assets are client presentation
adapters, not a vanilla GUI fallback. The current vanilla Anvil flow remains in
place only until the lock-migration phase. Until then, preserve its existing
reservation, cancellation, fail-closed, password, and exact item-accounting
behavior.

---

# GUI Delivery Gates

Phase 2 foundation work uses the normal production `jar` output for
`releaseArtifact` and performs the first production universal-JAR inspection
after split-source-set and metadata wiring. Gate 2 must inspect the
expanded `fabric.mod.json`, environment `*`, `main` and `client` entrypoints,
client classes/resources, mixin configuration, and the included SQLite
dependency. The 26.3 non-obfuscated lane uses the runtime namespace directly;
no remapping task is required.

Phase 5 repeats release/artifact validation for the final universal JAR, including
its bundled client classes and lock item assets; it is not the first proof of
packaging correctness.

---

# Commands

Minecraft commands are input adapters.

A command should:

1. parse Brigadier arguments
2. validate syntax
3. check administrative access through the authorization abstraction
4. call an application service
5. format the result

The current command policy rejects every player-originated command from a
non-administrator, including an authenticated non-administrator. Umbrellaz
command adapters still perform their own administrator check. Any future
exception must be introduced through the authorization abstraction; it must
not weaken the current fail-closed behavior.

Example:

```text
/umbrellaz whitelist add <player>
```

Expected implementation flow:

```text
WhitelistCommand
      ↓
WhitelistService
      ↓
WhitelistRepository
```

The command must not contain SQL.

---

# Services

Services own application behavior.

Examples:

```text
WhitelistService
AuthService
PlayerService
```

A service may:

- coordinate repositories
- manipulate cache
- apply application rules
- return async results
- coordinate multiple domain operations

A service must not become an all-purpose god class.

Split a service when independent behavior can be understood and tested separately.

---

# Use Cases

The architecture is use-case oriented, but does not require one class per use case.

For example, operations:

```text
add player to whitelist
remove player from whitelist
check whitelist
list whitelist
```

may reasonably live inside:

```text
WhitelistService
```

If a workflow grows substantially, extract it into a dedicated use-case class.

Do not prematurely create:

```text
AddWhitelistUseCase
DeleteWhitelistUseCase
FindWhitelistUseCase
ListWhitelistUseCase
```

for trivial operations.

---

# Repositories

Repositories provide persistence access.

Example API:

```text
findByUuid
existsByUuid
findAll
save
delete
```

Repositories should expose domain-oriented operations instead of generic SQL abstractions.

Avoid creating a generic repository framework.

Do not create:

```text
BaseRepository<T>
GenericRepository<T>
CrudRepository<T>
```

unless actual repeated requirements justify it.

---

# SQLite

SQLite is the initial persistent database.

Database path:

```text
config/umbrellaz/umbrellaz.db
```

Recommended pragmas:

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA synchronous=NORMAL;
PRAGMA busy_timeout=5000;
```

Connection configuration belongs in infrastructure.

Repositories should not configure SQLite pragmas.

---

# Database Thread

SQLite/JDBC work must not execute on the Minecraft server thread.

Use:

```text
DatabaseExecutor
```

The initial implementation should use:

```java
Executors.newSingleThreadExecutor(...)
```

A single database thread is intentional.

For the current workload it provides:

- serialized writes
- predictable ordering
- reduced locking complexity
- simple concurrency behavior

Do not replace it with a large executor pool without evidence that this is necessary.

---

# Async API

Application operations requiring database access should expose asynchronous results.

Prefer:

```java
CompletableFuture<Boolean>
CompletableFuture<Optional<Player>>
CompletableFuture<List<WhitelistEntry>>
```

Do not block waiting for these results on the Minecraft server thread.

Avoid:

```java
future.get();
future.join();
```

inside command execution or Fabric server events.

---

# Minecraft Thread Boundary

Minecraft objects are not general-purpose thread-safe objects.

Do not perform actions like these from the database thread:

```text
teleport player
send message
kick player
modify inventory
change game mode
modify world
execute command
```

Return to the server thread.

Conceptually:

```java
future.thenAccept(result -> {
    server.execute(() -> {
        // Minecraft interaction
    });
});
```

---

# Cache

Frequently accessed authorization information must live in memory.

Examples:

```text
Set<UUID> whitelistedPlayers
Map<UUID, AuthSession> authSessions
```

Do not treat the database as a request-time permission system.

Database:

```text
persistent source of truth
```

Cache:

```text
runtime source for hot-path checks
```

Any mutation must keep the persistence layer and runtime state consistent.

---

# Cache Mutation Ordering

For administrative mutations, prefer this flow:

```text
request
  ↓
persist to SQLite
  ↓
persistence succeeds
  ↓
update cache
  ↓
apply runtime state
```

If persistence fails, do not pretend the operation succeeded.

---

# Authentication State

Authentication state is runtime state.

Suggested representation:

```java
enum AuthState {
    BLOCKED,
    AUTHENTICATED
}
```

A session may contain:

```text
player UUID
state
authenticatedAt
```

Keep it simple initially.

Do not store unnecessary Minecraft objects inside long-lived domain state.

---

# Fail Closed

Authorization must fail closed.

If:

- database is unavailable
- cache has not finished loading
- player status is unknown
- an authorization operation failed

then the player is treated as blocked.

Never temporarily grant access because the authorization state is unavailable.

---

# Player Lifecycle

On join:

```text
player joins
    ↓
session starts as BLOCKED
    ↓
resolve player record
    ↓
resolve whitelist state
    ↓
AUTHORIZED?
   ↙         ↘
yes          no
 ↓            ↓
AUTHENTICATED BLOCKED
```

The player must not gain unrestricted interaction while asynchronous authorization is still pending.

---

# Blocking Unauthorized Players

Blocked players should not be able to:

- walk away from the restricted area
- break blocks
- place blocks
- attack entities
- use items
- interact with blocks
- interact with entities
- drop items
- collect items
- execute ordinary commands
- meaningfully manipulate inventory

Do not query SQLite during any of these checks.

Use the in-memory auth state.

---

# Movement Restrictions

Avoid teleporting blocked players every server tick.

Prefer event-based or threshold-based enforcement.

If repositioning is required:

- only reposition when necessary
- keep the implementation inexpensive
- avoid creating continuous teleport loops

---

# Restricted Spawn

Restricted spawn should eventually be configurable.

Desired configuration model:

```text
world
x
y
z
yaw
pitch
```

Keep the initial configuration system simple.

Do not introduce a general configuration framework unless required.

---

# Player Identity

Always persist Minecraft UUID.

Example player record:

```text
uuid
username
created_at
updated_at
```

UUID is authoritative.

Username is informational.

When a known UUID joins with a new username, update the stored username.

---

# Offline Players

Do not design whitelist operations around `ServerPlayer`.

Application models must represent offline users independently.

This allows future commands to modify players who are not currently connected.

Do not introduce external Mojang HTTP lookups unless explicitly required.

---

# Migrations

Database schema changes must use migrations.

Do not scatter:

```sql
CREATE TABLE
ALTER TABLE
```

inside repositories.

Maintain the migration controller table:

```text
_umbrellaz_migrations
```

Migration files must live under:

```text
src/main/resources/db/migrations/
```

Each migration must use the stable format `timestamp_name.sql`.

Example:

```text
20260926180100_create_players.sql
20260926180200_create_whitelist.sql
```

The controller stores the migration id, the timestamp parsed from the filename,
and the execution timestamp. Migration execution must be deterministic and
idempotent at the migration-runner level.

A migration must not run twice.

---

# Initial Tables

Conceptual initial schema:

```text
players
```

with:

```text
uuid
username
created_at
updated_at
```

and:

```text
whitelist
```

with:

```text
player_uuid
created_at
created_by
```

Administrative command access is stored separately in:

```text
_umbrellaz_administrators
```

It is keyed by player UUID and contains `created_at` and `created_by`. Do not
use Minecraft operator levels as the application's authorization source. The
server console may bootstrap administration, while player UUIDs must be
inserted into this table before they can manage Umbrellaz commands.

Use proper foreign keys.

---

# Transactions

Use transactions when a persistence operation consists of multiple dependent SQL statements.

Keep transactions short.

Do not leave transactions open while executing Minecraft code or waiting for another thread.

---

# Logging

Use SLF4J.

Preferred logger pattern:

```java
LoggerFactory.getLogger(Umbrellaz.MOD_ID)
```

or class-specific loggers where appropriate.

Log useful operational events.

Do not log every authorization check or movement cancellation.

---

# Exceptions

Do not swallow exceptions.

Asynchronous errors must be observed.

When returning futures, make failure behavior explicit.

Database exceptions may be wrapped in application-specific runtime exceptions if it improves context.

Avoid deep exception hierarchies.

---

# Configuration

Configuration belongs under:

```text
config/
```

The infrastructure responsible for reading configuration must remain independent from application rules.

Do not access configuration files from random services.

Load configuration during bootstrap and pass necessary values into the relevant components.

---

# Tests

Core business logic should remain testable without Minecraft where practical.

Examples:

```text
WhitelistServiceTest
AuthServiceTest
```

Focus on observable behavior.

Useful scenarios:

- whitelisted player becomes authenticated
- unknown player remains blocked
- removing whitelist blocks an active session
- adding whitelist releases a blocked session
- persistence failure does not update cache incorrectly
- unknown authorization state fails closed

Tests should not require a Minecraft server unless testing Fabric integration specifically.

---

# GitHub Releases

The local `release` task builds and publishes the universal mod jar to GitHub:

```bash
gh auth login
./gradlew release
```

Alternatively, authenticate the GitHub CLI with `GH_TOKEN`. The release
depends on and builds the normal production `jar`, then validates and uploads
only that universal artifact; no sources jar or separate resource-pack artifact
is published.

The Minecraft 26.3 non-obfuscated lane uses the runtime namespace directly, so
the normal Gradle `jar` is the production universal artifact and no remapping
task is required. Final release validation inspects the JAR metadata,
entrypoints, client classes/resources, mixins, included SQLite dependency, and
bundled lock item assets.

The default release tag is `v${version}`. Override it when needed with
`./gradlew -PreleaseTag=v1.2.3 release`. If the tag does not exist, `gh release
create` creates it automatically. GitHub CLI refuses an existing release
instead of overwriting it, so rerunning a successful release is expected to
fail safely; choose a new tag for another release.

Publishing is an external side effect and the task is deliberately never
up-to-date. It validates the artifact and GitHub CLI before publishing. The
task does not store or print credentials; it uses the GitHub CLI's normal
authentication.

---

# Comments

Do not add comments to source code unless explicitly requested.

Prefer code whose structure and names explain behavior.

Documentation belongs primarily in these project documents rather than inline commentary.

---

# Formatting

Use the project's formatting convention consistently.

Avoid unrelated formatting changes in the same commit/task.

Do not reformat entire files when making a small change unless formatting is already broken.

---

# Dependencies

Add libraries only when they provide meaningful value.

Current expected external dependencies are approximately:

```text
Fabric
Fabric API
SQLite JDBC
JUnit
```

Avoid framework-heavy solutions.

Do not add:

- Spring
- Guice
- Hibernate
- JPA
- reactive frameworks

for this project.

---

# Performance

The Minecraft server thread is the primary performance constraint.

Be especially careful with:

- ticks
- player movement
- entity interactions
- world operations
- chunk operations

Application administration features should add negligible overhead.

Avoid allocations and database access in event paths that may execute many times per second.

---

# Future Modules

New features should generally become their own modules.

Examples:

```text
roles/
permissions/
homes/
teleport/
bans/
mutes/
economy/
```

A new module should not modify unrelated modules unnecessarily.

Cross-module behavior should happen through explicit services or contracts.

---

# Definition of Done

A change is complete when:

- architecture rules are preserved
- code compiles
- tests pass
- no blocking DB operation runs on the Minecraft thread
- no Minecraft mutation happens on the DB thread
- relevant tests exist
- errors are handled
- new architecture decisions are documented
