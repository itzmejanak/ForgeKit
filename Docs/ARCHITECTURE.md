# ForgeKit — Architecture Specification

**Document:** `ARCHITECTURE.md`  
**Status:** Proposed / Architecture Baseline  
**Version:** 1.0.0  
**Platform:** Android  
**Primary language:** Kotlin  
**UI:** Jetpack Compose  
**Execution environment:** Embedded Termux-derived runtime  
**Package format:** `.forge`

---

## 1. Architecture Goal

ForgeKit is an Android application that allows users to run powerful scripting and command-line workloads through a native Android GUI.

The user should interact with:

- Android-native screens
- forms
- buttons
- progress indicators
- file pickers
- notifications
- logs
- job history

The actual workload may be implemented using:

- Python
- Node.js
- Bash
- Ruby
- Perl
- Go
- Rust
- C/C++
- Termux packages
- arbitrary Termux-compatible executables

The execution environment is embedded into ForgeKit rather than requiring a separately installed Termux application.

The architectural principle is:

> **ForgeKit owns orchestration, UX, policy, packages, permissions, and lifecycle.  
> The embedded Termux runtime owns Linux/POSIX-compatible execution and package infrastructure.**

ForgeKit must not artificially reduce the capabilities of the underlying Termux environment.

---

# 2. Core Architectural Decision

## 2.1 Embedded Termux, not "Termux-like"

ForgeKit will not implement a simplified custom shell/runtime.

It will integrate a maintained Termux-derived runtime into the ForgeKit application.

Conceptually:

```text
Android
└── ForgeKit APK
    │
    ├── ForgeKit Application Layer
    │
    ├── ForgeKit Plugin Platform
    │
    ├── ForgeKit Runtime Bridge
    │
    └── Embedded Termux Runtime
        ├── shell
        ├── bootstrap environment
        ├── apt/pkg
        ├── Termux packages
        ├── native executables
        ├── interpreters
        ├── libraries
        └── services
```

The Termux-derived layer must remain as close as practical to upstream Termux behavior.

Termux's current architecture already separates the Android application from the packages distributed inside the environment. Its bootstrap archives provide the minimal environment required to start the shell, and the package system supplies the rest.

ForgeKit should preserve this model instead of replacing it.

---

# 3. Non-Negotiable Architectural Requirements

The following are architecture constraints.

### R1 — Full runtime capability

ForgeKit plugins must be able to use the capabilities available to the embedded Termux environment.

ForgeKit must not restrict plugins to a small predefined set such as:

```text
Python only
Node only
Shell only
```

Those are supported runtimes, not limits.

---

### R2 — UI/runtime separation

A plugin's Android UI must never be coupled directly to:

- ProcessBuilder
- Termux internals
- shell parsing
- Android Activity internals
- package-manager implementation details

The UI communicates through ForgeKit contracts.

---

### R3 — Runtime abstraction

ForgeKit must expose an internal runtime interface.

The application layer must not depend directly on implementation-specific Termux classes.

```kotlin
interface ForgeRuntime {
    suspend fun initialize(): RuntimeState
    suspend fun execute(request: ExecutionRequest): ExecutionHandle
    suspend fun terminate(processId: String)
    suspend fun install(dependency: RuntimeDependency)
    suspend fun inspect(): RuntimeCapabilities
}
```

The first implementation is:

```kotlin
class EmbeddedTermuxRuntime : ForgeRuntime
```

This is not intended to migrate away from Termux later. It exists to keep architectural boundaries clean.

---

### R4 — Plugin API stability

Plugins must communicate through versioned ForgeKit contracts.

Plugins must not directly depend on private Kotlin classes.

---

### R5 — Android and Linux responsibilities must remain separate

Android owns:

- UI
- lifecycle
- permissions
- notifications
- user file selection
- application security
- app settings
- plugin management

The embedded Linux environment owns:

- shell
- executable processes
- Linux-compatible packages
- language runtimes
- command-line tools
- package manager
- runtime filesystem

---

# 4. High-Level System

```text
┌─────────────────────────────────────────────────────────────┐
│                         Android OS                          │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                     ForgeKit APK                      │  │
│  │                                                       │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │                 Compose UI                     │  │  │
│  │  └───────────────────────┬─────────────────────────┘  │  │
│  │                          │                            │  │
│  │  ┌───────────────────────▼─────────────────────────┐  │  │
│  │  │              Application Layer                 │  │  │
│  │  │  Plugin Manager / Jobs / Permissions / Files   │  │  │
│  │  └───────────────────────┬─────────────────────────┘  │  │
│  │                          │                            │  │
│  │  ┌───────────────────────▼─────────────────────────┐  │  │
│  │  │                 Plugin Platform                │  │  │
│  │  │ Manifest / UI / Protocol / Dependency / Policy│  │  │
│  │  └───────────────────────┬─────────────────────────┘  │  │
│  │                          │                            │  │
│  │  ┌───────────────────────▼─────────────────────────┐  │  │
│  │  │              Runtime Bridge                    │  │  │
│  │  │      ForgeRuntime / IPC / Process Control      │  │  │
│  │  └───────────────────────┬─────────────────────────┘  │  │
│  │                          │                            │  │
│  │  ┌───────────────────────▼─────────────────────────┐  │  │
│  │  │          Embedded Termux Runtime               │  │  │
│  │  │ Shell / Bootstrap / pkg / apt / binaries       │  │  │
│  │  └───────────────────────┬─────────────────────────┘  │  │
│  │                          │                            │  │
│  │  ┌───────────────────────▼─────────────────────────┐  │  │
│  │  │                Plugin Processes                │  │  │
│  │  │ Python / Node / Bash / Go / Rust / native etc. │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  │                                                       │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

# 5. Android Application Architecture

Use a modular architecture.

```text
forgekit/
│
├── app/
│
├── core/
│   ├── common/
│   ├── model/
│   ├── database/
│   ├── logging/
│   ├── security/
│   └── filesystem/
│
├── platform/
│   ├── android/
│   ├── notifications/
│   ├── storage/
│   ├── permissions/
│   └── lifecycle/
│
├── runtime/
│   ├── api/
│   ├── bridge/
│   ├── termux/
│   └── bootstrap/
│
├── plugins/
│   ├── api/
│   ├── manager/
│   ├── installer/
│   ├── validator/
│   ├── resolver/
│   ├── registry/
│   └── protocol/
│
├── jobs/
│   ├── manager/
│   ├── scheduler/
│   ├── persistence/
│   └── execution/
│
├── ui/
│   ├── design-system/
│   ├── navigation/
│   ├── home/
│   ├── plugins/
│   ├── jobs/
│   ├── settings/
│   └── terminal/
│
└── termux-upstream/
    ├── app/
    ├── bootstrap/
    └── integration/
```

The exact Gradle module split may evolve, but the dependency direction must remain stable.

---

# 6. Dependency Direction

Allowed:

```text
UI
 ↓
Application
 ↓
Domain
 ↓
Interfaces
 ↓
Infrastructure
```

Example:

```text
PluginScreen
    ↓
PluginViewModel
    ↓
RunPluginUseCase
    ↓
PluginExecutor
    ↓
ForgeRuntime
    ↓
EmbeddedTermuxRuntime
```

Not allowed:

```text
Compose UI
   ↓
Termux private class
```

Not allowed:

```text
Plugin
   ↓
Android Activity
```

Not allowed:

```text
Plugin
   ↓
database implementation
```

---

# 7. Embedded Termux Strategy

ForgeKit will maintain a controlled fork/integration of the relevant Termux source.

The upstream Termux repositories remain the authoritative source for Termux runtime behavior.

ForgeKit should avoid rewriting Termux internals unless required for integration.

The preferred strategy is:

```text
Upstream Termux
       │
       │ controlled update
       ▼
ForgeKit termux-upstream
       │
       ▼
ForgeKit integration layer
       │
       ▼
Embedded runtime
```

Do not copy individual Termux utilities into unrelated ForgeKit modules.

---

# 8. Termux Runtime Ownership

The embedded Termux layer owns:

### Environment

```text
$PREFIX
$HOME
$TMPDIR
PATH
LD_LIBRARY_PATH
TERMUX_*
```

### Package system

```text
apt
pkg
dpkg
Termux repositories
```

### Linux userspace

```text
bash
coreutils
grep
sed
awk
find
tar
git
etc.
```

### Language runtimes

```text
python
nodejs
ruby
perl
etc.
```

### Native binaries

```text
ffmpeg
imagemagick
openssl
sqlite
etc.
```

ForgeKit should not recreate these systems.

---

# 9. ForgeKit Ownership

ForgeKit owns:

```text
Plugin installation
Plugin updates
Plugin removal
Plugin metadata
Plugin UI
Plugin permissions
Plugin jobs
Plugin history
Plugin logs
Plugin dependency orchestration
Plugin sandbox policy
Android file access
Android notifications
Android lifecycle
Runtime health
Runtime initialization
Runtime repair
Runtime diagnostics
```

---

# 10. Filesystem Architecture

ForgeKit needs clear filesystem boundaries.

Conceptually:

```text
ForgeKit/
│
├── app/
│   └── Android application data
│
├── forge/
│   ├── plugins/
│   │   └── <plugin-id>/
│   │       ├── manifest.json
│   │       ├── ui/
│   │       ├── runtime/
│   │       ├── data/
│   │       ├── cache/
│   │       └── logs/
│   │
│   ├── jobs/
│   │
│   ├── registry/
│   │
│   └── metadata/
│
└── termux/
    ├── home/
    ├── prefix/
    ├── tmp/
    └── packages/
```

The actual Android filesystem paths must be determined by the implementation and Android version. The logical separation is the architectural contract.

---

# 11. Important Filesystem Rule

Do not place ForgeKit plugin data directly into Termux's global `$HOME` without a defined boundary.

Instead:

```text
ForgeKit plugin
      ↓
/forge/plugins/<id>/
      ↓
runtime process
```

The runtime can still access the broader Termux environment where explicitly required.

ForgeKit's policy layer determines what the plugin is allowed to access.

This preserves Termux capability without making ForgeKit's plugin lifecycle dependent on random files in the global home directory.

---

# 12. Plugin Package

The official package format is:

```text
*.forge
```

A `.forge` package is an archive with a defined internal schema.

Example:

```text
example.forge
│
├── manifest.json
├── ui/
│   └── main.json
├── runtime/
│   ├── main.py
│   └── ...
├── dependencies/
│   ├── requirements.txt
│   └── package.json
├── assets/
│   └── icon.png
├── docs/
│   └── README.md
└── tests/
    └── ...
```

---

# 13. Plugin Manifest

Example:

```json
{
  "schema": "forgekit.plugin/v1",
  "id": "com.example.tool",
  "name": "Example Tool",
  "version": "1.0.0",

  "runtime": {
    "type": "python",
    "version": ">=3.11"
  },

  "entrypoint": "runtime/main.py",

  "ui": {
    "entry": "ui/main.json"
  },

  "dependencies": {
    "termux": [
      "python"
    ],
    "python": [
      "requests"
    ]
  },

  "permissions": [
    "network",
    "files.read",
    "files.write"
  ]
}
```

The manifest is declarative.

It tells ForgeKit what is required.

It does not grant itself permissions.

---

# 14. UI Architecture

Plugins must not ship arbitrary Android Activities as the normal plugin model.

The primary plugin UI is declarative.

```text
ui.json
    ↓
ForgeKit UI Schema
    ↓
Compose renderer
    ↓
Android UI
```

Supported controls can include:

```text
text
password
number
select
checkbox
switch
slider
file
directory
date
time
button
progress
table
image
markdown
log
```

Later versions may support richer custom UI through a controlled extension API.

---

# 15. Plugin Runtime Protocol

The plugin process communicates using a versioned structured protocol.

Do not make UI behavior depend on human-readable stdout.

Example request:

```json
{
  "protocol": "forgekit/1",
  "type": "invoke",
  "requestId": "abc123",
  "action": "download",
  "input": {
    "url": "https://example.com/file"
  }
}
```

Example event:

```json
{
  "protocol": "forgekit/1",
  "type": "progress",
  "requestId": "abc123",
  "value": 0.72,
  "message": "Downloading"
}
```

Example result:

```json
{
  "protocol": "forgekit/1",
  "type": "result",
  "requestId": "abc123",
  "status": "success",
  "output": {
    "file": "/path/to/result"
  }
}
```

---

# 16. stdout and stderr

stdout/stderr remain fully available.

They are not the primary application protocol.

They are classified as:

```text
stdout → diagnostic / runtime output
stderr → diagnostic / error output
structured protocol → application communication
```

This preserves full Termux behavior.

A plugin can still run arbitrary commands that produce normal terminal output.

---

# 17. Interactive Terminal Capability

ForgeKit must include an advanced terminal surface.

This is critical because the goal is not to destroy Termux capability.

The user can choose:

```text
Plugin UI
Terminal
Logs
Files
```

The Terminal screen provides access to the embedded Termux shell.

Therefore ForgeKit has two modes:

```text
GUI mode
    ↓
Plugin-defined interface

Terminal mode
    ↓
Full embedded Termux shell
```

This prevents ForgeKit from becoming a restricted wrapper.

---

# 18. Runtime Modes

The runtime supports:

### Mode A — Plugin execution

```text
ForgeKit UI
    ↓
Plugin protocol
    ↓
Process
```

### Mode B — Terminal

```text
ForgeKit Terminal
    ↓
Shell
```

### Mode C — Background job

```text
Job Manager
    ↓
Runtime
    ↓
Long-running process
```

### Mode D — Service

```text
ForgeKit service manager
    ↓
Termux-compatible service
```

---

# 19. Dependency Architecture

Dependencies have multiple levels.

```text
Level 0:
ForgeKit runtime

Level 1:
Termux package

Level 2:
Language runtime/package

Level 3:
Plugin-local dependencies
```

Example:

```text
Plugin
 ↓
Python
 ↓
requests
 ↓
OpenSSL
 ↓
Termux libraries
```

ForgeKit resolves and verifies the dependency graph.

The actual installation is performed by the appropriate package manager.

---

# 20. Dependency Ownership

ForgeKit:

```text
declares
resolves
checks
tracks
reports
```

Termux:

```text
installs
upgrades
removes
provides
```

Language package managers:

```text
pip
npm
cargo
gem
etc.
```

remain responsible for their ecosystems.

ForgeKit should orchestrate them rather than replacing them.

---

# 21. Environment Isolation

The default policy is:

```text
Termux environment = shared platform runtime
Plugin environment = isolated application data/dependencies where practical
```

For Python:

```text
plugin/
└── environment/
    └── .venv/
```

For Node:

```text
plugin/
└── environment/
    └── node_modules/
```

For binaries:

```text
plugin/
└── bin/
```

Global Termux packages remain available when the plugin explicitly requires them.

---

# 22. Full Termux Capability Principle

Isolation must not mean capability destruction.

A plugin may request:

```text
shell
network
filesystem
process execution
native executable
Termux package
Android bridge
```

ForgeKit evaluates the request.

Where policy allows it, the plugin receives the capability.

This is preferable to pretending that all plugins can safely run in a tiny artificial sandbox.

---

# 23. Permissions

ForgeKit permissions are separate from Android permissions.

Example:

```text
ForgeKit permission:
network
```

Android permission:

```text
INTERNET
```

Both layers may exist.

Plugin:

```text
network
files.read
files.write
notifications
background
```

ForgeKit evaluates the plugin request.

Android handles Android-specific permissions.

---

# 24. Security Model

Rules.md is NOT a security boundary.

It is a development contract.

Security is enforced by:

```text
Manifest validation
Package verification
Permission enforcement
Signature verification
Runtime policy
Filesystem policy
Process lifecycle controls
Dependency verification
```

---

# 25. Plugin Trust Levels

Supported trust states:

```text
OFFICIAL
VERIFIED
COMMUNITY
UNKNOWN
BLOCKED
```

Example:

```text
Official
✓ Signed
✓ Verified
✓ Published by ForgeKit

Community
✓ Signature known
? Publisher not verified

Unknown
⚠ User imported package
⚠ Not verified
```

The user must explicitly approve risky packages.

---

# 26. Package Signing

`.forge` packages should support cryptographic signatures.

Conceptually:

```text
package
manifest
files
hashes
signature
publisher identity
```

ForgeKit verifies:

```text
integrity
signature
publisher
schema
compatibility
```

Before installation.

---

# 27. AI-Generated Plugins

AI-generated plugins are first-class citizens.

The platform provides:

```text
Rules.md
Plugin specification
UI specification
Protocol specification
Security specification
SDK
Validator
Test framework
```

AI agent workflow:

```text
Read Rules.md
     ↓
Understand Plugin API
     ↓
Generate package
     ↓
Run validator
     ↓
Run tests
     ↓
Build .forge
     ↓
Sign/package
```

---

# 28. Rules.md

`Rules.md` is versioned.

Example:

```text
forgekit/rules/v1
forgekit/rules/v2
```

The package declares:

```json
{
  "rules": "forgekit/v1"
}
```

This prevents a future Rules.md update from silently invalidating old packages.

---

# 29. Package Validation

ForgeKit should provide a validator.

Conceptually:

```text
forgekit validate package.forge
```

Checks:

```text
✓ archive structure
✓ manifest schema
✓ UI schema
✓ permissions
✓ dependency declarations
✓ runtime compatibility
✓ protocol compatibility
✓ signature
✓ prohibited files
✓ package size
✓ entrypoint
```

---

# 30. Job Architecture

Every plugin execution is represented as a Job.

```text
Job
├── id
├── pluginId
├── state
├── createdAt
├── startedAt
├── completedAt
├── processId
├── input
├── output
├── error
└── logs
```

States:

```text
QUEUED
PREPARING
INSTALLING_DEPENDENCIES
STARTING
RUNNING
PAUSED
CANCELLING
COMPLETED
FAILED
CANCELLED
```

---

# 31. Long-Running Work

Android lifecycle cannot be trusted to keep arbitrary processes alive forever.

ForgeKit therefore owns a job lifecycle layer.

For long-running work:

```text
Android foreground/background execution mechanism
              ↓
ForgeKit Job Manager
              ↓
Embedded Termux process
```

The process must be recoverable and observable.

---

# 32. Runtime Recovery

ForgeKit must be able to detect:

```text
runtime missing
bootstrap corrupted
package database corrupted
dependency failure
process crash
storage failure
```

Runtime manager provides:

```text
health()
repair()
reinitialize()
diagnostics()
```

Never require the user to manually reinstall ForgeKit for a recoverable runtime problem.

---

# 33. Bootstrap

The embedded runtime contains a bootstrap environment.

Architecture:

```text
APK
 │
 ├── ForgeKit code
 │
 └── architecture-specific Termux bootstrap
              │
              ▼
        runtime filesystem
```

At first initialization:

```text
verify bootstrap
        ↓
extract/bootstrap
        ↓
initialize environment
        ↓
initialize package manager
        ↓
health check
```

Bootstrap archives are part of the Termux build architecture and are generated from the Termux package infrastructure.

---

# 34. CPU Architectures

Minimum supported architecture should initially be:

```text
arm64-v8a
```

Optional:

```text
armeabi-v7a
x86_64
x86
```

The architecture matrix must be decided before publishing releases.

Native plugins and Termux packages must match the device ABI.

---

# 35. Android Storage

Do not assume unrestricted filesystem access.

Use Android Storage Access Framework where user-controlled external files are involved.

Logical flow:

```text
Plugin requests file
       ↓
ForgeKit file picker
       ↓
User selects
       ↓
ForgeKit grants controlled access
       ↓
Runtime receives usable path/copy/reference
```

Internal runtime files remain inside application-controlled storage.

---

# 36. Android APIs from Plugins

ForgeKit should expose Android capabilities through a controlled bridge.

Example:

```text
Plugin
 ↓
ForgeKit capability API
 ↓
Android implementation
```

Capabilities may eventually include:

```text
notifications
camera
microphone
clipboard
share
file picker
location
Bluetooth
sensors
contacts
```

Do not expose arbitrary Android Context/Activity objects to plugins.

---

# 37. Termux API Integration

Termux already has a model for exposing Android functionality to command-line programs.

ForgeKit should preserve compatibility where practical rather than inventing incompatible replacements.

However, because ForgeKit embeds the runtime, Android integration can eventually be implemented through ForgeKit-owned bridges as well.

The compatibility goal is:

```text
Existing Termux-oriented scripts
        ↓
minimal/no changes
        ↓
ForgeKit embedded runtime
```

---

# 38. Terminal UI

The terminal UI is an optional ForgeKit surface.

It should use the embedded Termux execution/session infrastructure rather than creating a second shell implementation.

```text
Compose
   ↓
Terminal View
   ↓
Termux session
   ↓
shell
```

The terminal is an advanced escape hatch.

GUI users never need to open it.

Power users retain full control.

---

# 39. Database

Use Room for ForgeKit metadata.

Do NOT use Room for the Termux package database.

ForgeKit database:

```text
plugins
plugin_versions
plugin_permissions
plugin_sources
jobs
job_events
runtime_state
settings
trusted_publishers
```

Termux maintains its own package/runtime state.

---

# 40. Event Architecture

Use a typed internal event model.

Examples:

```kotlin
sealed interface ForgeEvent {
    data class JobStarted(...)
    data class JobProgress(...)
    data class JobOutput(...)
    data class JobCompleted(...)
    data class RuntimeStateChanged(...)
    data class DependencyInstalled(...)
}
```

UI observes application state rather than reading processes directly.

---

# 41. Logging

Separate:

```text
Application logs
Runtime logs
Plugin logs
Job logs
Security audit logs
```

Do not mix them into one file.

Sensitive values must be redacted.

---

# 42. Secret Management

Plugins must not receive secrets from environment variables by default.

ForgeKit should eventually provide:

```text
Secret Store
```

Example:

```text
API_KEY
TOKEN
PASSWORD
```

Plugin requests:

```text
secret("OPENAI_API_KEY")
```

ForgeKit resolves it according to permission/policy.

Secrets must not appear in:

```text
UI logs
stdout
job history
crash reports
```

---

# 43. Network Architecture

Plugins may use network through the runtime.

ForgeKit tracks:

```text
network permission
optional network policy
```

Do not implement a fake HTTP layer that limits arbitrary Termux applications.

Python requests, curl, wget, Node fetch, etc. should continue to work normally inside the runtime.

---

# 44. Native Code

Native programs are first-class runtime citizens.

A plugin may contain:

```text
ARM64 executable
shared library
native extension
```

subject to package compatibility and security validation.

ForgeKit must not assume that every plugin is interpreted code.

---

# 45. Services

Long-running services are separate from ordinary jobs.

Example:

```text
Plugin:
Local Web Server
```

Architecture:

```text
ForgeKit Service Manager
        ↓
Termux process/service
        ↓
127.0.0.1:8080
```

Service lifecycle:

```text
START
STOP
RESTART
STATUS
LOGS
```

---

# 46. Plugin Lifecycle

```text
IMPORT
  ↓
VERIFY
  ↓
VALIDATE
  ↓
RESOLVE
  ↓
INSTALL
  ↓
INITIALIZE
  ↓
READY
  ↓
RUN
  ↓
UPDATE / DISABLE / REMOVE
```

Removal must not blindly delete shared runtime dependencies.

Dependency reference counting may be required.

---

# 47. Update Strategy

ForgeKit has separate update channels:

```text
ForgeKit application
Termux runtime
Termux packages
Plugin packages
```

They must not be treated as one version.

Example:

```text
ForgeKit: 1.4.0
Runtime schema: 2
Termux base: 0.x
Plugin: 4.1.2
```

---

# 48. Termux Upstream Synchronization

The embedded Termux layer must have an explicit upstream process.

```text
upstream Termux
      ↓
review changes
      ↓
update fork/integration
      ↓
build
      ↓
ForgeKit integration tests
      ↓
runtime compatibility tests
      ↓
release
```

Never casually copy upstream source into ForgeKit.

Maintain traceability to upstream commits/tags.

---

# 49. Build Architecture

The build pipeline:

```text
ForgeKit source
      │
      ├── Kotlin/Compose
      │
      ├── ForgeKit native components
      │
      └── Termux-derived components
               │
               ▼
       architecture-specific build
               │
               ▼
             APK
```

Release artifacts should be reproducible.

---

# 50. Testing Layers

### Unit

```text
manifest parser
dependency resolver
permission policy
protocol
state machine
```

### Integration

```text
ForgeKit ↔ runtime
runtime ↔ package manager
plugin ↔ protocol
Android ↔ filesystem
```

### Runtime

```text
Python
Node
Bash
native binaries
network
files
background execution
```

### Device

Test on:

```text
Android versions
ARM64
low-memory device
high-memory device
battery restrictions
storage restrictions
```

---

# 51. Plugin Compatibility Tests

ForgeKit should have a standard compatibility suite.

Example:

```text
forgekit-test-plugin
```

Tests:

```text
stdin
stdout
stderr
exit code
environment variables
filesystem
network
signals
process termination
JSON protocol
large output
binary output
long-running process
background execution
```

---

# 52. Process Control

ForgeKit needs reliable:

```text
start
stdin
stdout
stderr
signal
terminate
kill
exit
resource observation
```

The implementation must not assume that every process is cooperative.

---

# 53. Signals

The runtime must preserve normal Unix-style process semantics where Android/Termux permits them.

Examples:

```text
SIGTERM
SIGINT
SIGKILL
```

Plugin cancellation:

```text
Cancel
 ↓
SIGTERM
 ↓
grace period
 ↓
SIGKILL if necessary
```

---

# 54. Resource Management

Track:

```text
CPU
memory
storage
process count
network activity where feasible
```

Do not impose arbitrary low limits merely because ForgeKit has a GUI.

Limits should be policy-driven.

---

# 55. Plugin UI Security

Declarative UI cannot:

```text
execute arbitrary Kotlin
instantiate arbitrary Activity
access Android Context directly
```

It can:

```text
display data
collect input
request declared capability
invoke plugin action
show progress
show files/results
```

---

# 56. Custom UI Extensions

A future extension mechanism may allow custom Android UI.

It should be a separate trust class.

```text
Declarative plugin
    → low complexity / controlled

Native extension plugin
    → higher trust / Android code
```

Do not make custom Android code the default plugin mechanism.

---

# 57. Marketplace Architecture

Marketplace is not part of the runtime.

It is a source provider.

```text
Plugin Source
├── Official Registry
├── Git repository
├── Local file
├── URL
└── Private registry
```

All sources eventually produce:

```text
.forge
```

The installer doesn't care where the package came from.

---

# 58. Package Sources

Use an abstraction:

```kotlin
interface PluginSource {
    suspend fun search(query: String): List<PluginDescriptor>
    suspend fun fetch(id: String, version: String): PluginArtifact
}
```

Implementations:

```text
OfficialRegistrySource
GitSource
LocalFileSource
UrlSource
PrivateRegistrySource
```

---

# 59. No Marketplace Lock-In

Users can import:

```text
my-tool.forge
```

directly.

ForgeKit must not require a central marketplace.

---

# 60. Offline Operation

ForgeKit should work offline for already-installed plugins.

Offline:

```text
installed plugin
+ installed dependencies
= runnable
```

Network is required only when the plugin itself requires it or dependencies must be downloaded.

---

# 61. Backup and Restore

ForgeKit should provide:

```text
Backup
Restore
```

Backup categories:

```text
Plugin metadata
Plugin packages
Plugin data
ForgeKit settings
Selected runtime configuration
```

Termux runtime backup should be handled carefully because package state and architecture-specific binaries may not be portable.

---

# 62. Migration Strategy

The architecture intentionally avoids a future migration from:

```text
external Termux
```

to:

```text
embedded Termux
```

The first production architecture is already:

```text
ForgeKit
└── Embedded Termux runtime
```

Therefore future phases add features around the same runtime boundary.

---

# 63. Future Runtime Implementations

The abstraction allows other runtimes in theory:

```text
ForgeRuntime
├── EmbeddedTermuxRuntime
├── RemoteRuntime
└── TestRuntime
```

But ForgeKit production behavior must be designed around EmbeddedTermuxRuntime.

This abstraction exists for testing, maintenance, and future engineering flexibility—not as a planned migration away from Termux.

---

# 64. Android Application Modules

Recommended initial Gradle modules:

```text
:app

:core:model
:core:common
:core:database
:core:security
:core:filesystem
:core:logging

:runtime:api
:runtime:bridge
:runtime:termux
:runtime:bootstrap

:plugin:api
:plugin:manifest
:plugin:installer
:plugin:validator
:plugin:resolver
:plugin:protocol
:plugin:manager

:job:api
:job:manager
:job:persistence

:ui:design
:ui:home
:ui:plugins
:ui:jobs
:ui:terminal
:ui:settings

:termux:embedded
```

Do not create dozens of Gradle modules before they provide real dependency boundaries.

The list is an architectural target, not a requirement to implement everything on day one.

---

# 65. Package Naming

Example:

```text
com.forgekit.app
com.forgekit.core.*
com.forgekit.runtime.*
com.forgekit.plugin.*
com.forgekit.job.*
com.forgekit.ui.*
```

Embedded Termux code should remain clearly namespaced/separated according to its upstream licensing and source organization.

---

# 66. License Boundary

This is a critical architectural/legal requirement.

Termux components are distributed under open-source licenses, and the main Termux application is GPLv3.

If ForgeKit embeds and distributes modified Termux application code, the resulting distribution must comply with the applicable Termux licenses.

Do not design the project assuming that the Termux code can simply be statically/private-copied into a proprietary application without obligations.

Before the first public distribution:

```text
1. Inventory every Termux component.
2. Record license.
3. Record source origin and commit.
4. Preserve required copyright notices.
5. Provide required source/code availability.
6. Review whether ForgeKit components become subject to GPL obligations.
7. Review third-party package licenses.
```

This must be resolved before release, not after the application is published.

---

# 67. Third-Party Dependency Registry

Maintain:

```text
THIRD_PARTY_NOTICES
LICENSES/
UPSTREAM/
```

For every embedded third-party component record:

```text
name
version
source
license
repository
commit/tag
modifications
license obligations
```

---

# 68. Android Distribution Strategy

The embedded runtime makes ForgeKit a significantly larger application than a normal Kotlin application.

The release pipeline must consider:

```text
APK/AAB size
ABI splits
bootstrap size
native libraries
Termux packages
Google Play policies
dynamic downloads
```

Termux's own bootstrap architecture shows why this matters: bootstrap archives are packaged per architecture and contain the minimal environment required to initialize the runtime.

Do not assume that a universal APK is always the optimal distribution.

---

# 69. Initial Runtime Bootstrap Flow

```text
App installed
      ↓
ForgeKit starts
      ↓
RuntimeManager.initialize()
      ↓
Check ABI
      ↓
Verify bootstrap
      ↓
Extract bootstrap if necessary
      ↓
Initialize Termux filesystem
      ↓
Initialize package manager
      ↓
Run runtime health checks
      ↓
Runtime READY
```

---

# 70. Runtime State Machine

```text
NOT_INSTALLED
      ↓
INSTALLING
      ↓
INITIALIZING
      ↓
READY
      │
      ├── DEGRADED
      │
      ├── REPAIRING
      │
      └── FAILED
```

ForgeKit UI should expose this state.

---

# 71. Runtime Health Checks

Minimum:

```text
shell executable
environment variables
filesystem writable
package manager available
dynamic linker works
native executable works
Python/other runtimes only when installed
```

Health check must not assume Python or Node are part of the minimal bootstrap.

---

# 72. Plugin Installation Flow

```text
Import .forge
      ↓
Parse manifest
      ↓
Verify signature
      ↓
Validate schema
      ↓
Check runtime compatibility
      ↓
Resolve dependencies
      ↓
Show permissions
      ↓
User approval
      ↓
Install
      ↓
Run initialization
      ↓
READY
```

---

# 73. Plugin Execution Flow

```text
User opens plugin
      ↓
Load manifest
      ↓
Load UI schema
      ↓
Render Compose screen
      ↓
User enters data
      ↓
ForgeKit validates input
      ↓
Create Job
      ↓
Prepare environment
      ↓
Start process
      ↓
Protocol events
      ↓
Update UI
      ↓
Result
      ↓
Persist job history
```

---

# 74. Error Handling

Errors must be typed.

Examples:

```text
PluginManifestError
PluginSignatureError
DependencyError
RuntimeUnavailableError
PermissionDeniedError
ProcessStartError
ProcessCrashedError
ProtocolError
StorageError
```

Do not show raw stack traces as the primary user-facing error.

Advanced users can access technical logs.

---

# 75. Observability

Every execution gets:

```text
jobId
pluginId
runtimeId
processId
timestamps
exit code
status
logs
```

This enables debugging without requiring the user to reproduce everything from a shell.

---

# 76. Developer Mode

ForgeKit should include Developer Mode.

Features:

```text
Install unsigned plugin
View raw manifest
View runtime environment
View process information
Open terminal
View protocol events
Export logs
Reload plugin
Run validation
```

Developer Mode must have explicit warning/enablement.

---

# 77. User Mode

Normal users see:

```text
Home
Plugins
Jobs
Files
Settings
```

They don't need to know:

```text
Termux
bash
apt
PREFIX
LD_LIBRARY_PATH
```

unless they open advanced/runtime settings.

---

# 78. Advanced Runtime Screen

Power users can inspect:

```text
Runtime:
READY

Architecture:
arm64-v8a

Prefix:
...

Home:
...

Shell:
...

Package manager:
...

Installed packages:
...

Environment:
...
```

This preserves transparency.

---

# 79. Configuration Separation

ForgeKit configuration:

```text
ForgeKit DB
```

Termux configuration:

```text
Termux home/config
```

Plugin configuration:

```text
plugin data
```

Do not put all configuration into one giant JSON file.

---

# 80. Security Audit Log

Record security-sensitive events:

```text
plugin installed
permission granted
permission revoked
plugin updated
signature failure
unsigned package installed
runtime repair
native executable launched
```

Do not log secret values.

---

# 81. API Stability

Version all public plugin-facing interfaces.

```text
forgekit.plugin/v1
forgekit.protocol/v1
forgekit.ui/v1
forgekit.runtime/v1
```

Breaking changes require a new major version.

---

# 82. Architecture Rules

### Rule 1

Never let UI directly execute shell commands.

### Rule 2

Never let plugins access private Android APIs.

### Rule 3

Never parse human-readable terminal output as the primary protocol.

### Rule 4

Never assume Python is the only runtime.

### Rule 5

Never replace Termux's package manager with a ForgeKit clone.

### Rule 6

Never use Rules.md as a security mechanism.

### Rule 7

Never silently modify global plugin state.

### Rule 8

Never couple plugin packages to Kotlin implementation classes.

### Rule 9

Never make the marketplace mandatory.

### Rule 10

Never make the embedded runtime dependent on an external Termux installation.

---

# 83. Critical Product Boundary

ForgeKit is:

```text
Android Application Platform
+
Plugin System
+
Native GUI
+
Job Manager
+
Policy/Permission Layer
+
Embedded Termux Environment
```

ForgeKit is NOT:

```text
a Python app
a shell wrapper
a terminal-only application
a package manager replacement
a Linux emulator
a PRoot distribution
```

Termux remains the execution foundation.

---

# 84. Recommended Source Tree

A practical repository:

```text
ForgeKit/
│
├── app/
│
├── forgekit/
│   ├── core/
│   ├── runtime/
│   ├── plugins/
│   ├── jobs/
│   ├── security/
│   ├── storage/
│   └── ui/
│
├── termux/
│   ├── upstream/
│   ├── patches/
│   ├── bootstrap/
│   └── integration/
│
├── plugin-sdk/
│
├── plugin-examples/
│
├── tools/
│   ├── forge-validator/
│   ├── forge-builder/
│   └── forge-test/
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── RULES.md
│   ├── PLUGIN_SPEC.md
│   ├── UI_SPEC.md
│   ├── PROTOCOL.md
│   ├── SECURITY.md
│   └── CONTRIBUTING.md
│
├── third-party/
│   └── licenses/
│
└── README.md
```

---

# 85. Architecture Decision Records

Create ADRs from the beginning.

```text
docs/adr/
├── 0001-embedded-termux.md
├── 0002-plugin-format.md
├── 0003-plugin-protocol.md
├── 0004-runtime-boundary.md
├── 0005-filesystem-model.md
├── 0006-permission-model.md
├── 0007-package-signing.md
├── 0008-dependency-management.md
└── 0009-termux-upstream-strategy.md
```

Every major architectural change gets an ADR.

---

# 86. Final Architecture

The final intended system is:

```text
                         FORGEKIT
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│                    Android / Compose UI                    │
│                           │                                 │
│                           ▼                                 │
│                 Application / Domain                       │
│                           │                                 │
│          ┌────────────────┼────────────────┐                │
│          ▼                ▼                ▼                │
│      Plugins           Jobs           Permissions           │
│          │                │                │                │
│          └────────────────┼────────────────┘                │
│                           ▼                                 │
│                 Runtime Bridge/API                          │
│                           │                                 │
│                           ▼                                 │
│              Embedded Termux Runtime                       │
│                           │                                 │
│       ┌───────────────────┼───────────────────┐             │
│       ▼                   ▼                   ▼             │
│     Shell             Packages            Services          │
│       │                   │                   │             │
│       └───────────────────┼───────────────────┘             │
│                           ▼                                 │
│                 Arbitrary Termux                           │
│                 compatible workloads                        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

# 87. Final Architectural Principle

The most important design decision in ForgeKit is:

> **Do not build a GUI around a limited collection of scripts. Build an Android application platform around a complete embedded Termux execution environment.**

This means the architecture must preserve two worlds:

```text
                         ForgeKit
                            │
             ┌──────────────┴──────────────┐
             │                             │
          Android                      Linux userspace
             │                             │
        Compose UI                    Termux runtime
        Permissions                   Shell
        Jobs                          Packages
        Plugins                       Python
        Files                         Node
        Security                      Native binaries
        Notifications                 Services
             │                             │
             └──────────────┬──────────────┘
                            │
                     ForgeKit Protocol
```

The GUI is an interface to the runtime, not a replacement for it.

---

# 88. Architecture Completion Criteria

The architecture is considered implemented when:

```text
[ ] ForgeKit starts without external Termux
[ ] Embedded runtime initializes independently
[ ] Full Termux shell works
[ ] Termux package manager works
[ ] Native binaries work
[ ] Python works
[ ] Node works
[ ] Arbitrary compatible scripts work
[ ] Plugin UI works
[ ] Plugin protocol works
[ ] Dependencies resolve
[ ] Plugin permissions work
[ ] Long-running jobs work
[ ] Background execution works within Android limits
[ ] Plugin logs work
[ ] Terminal mode works
[ ] Runtime repair works
[ ] Plugin signing works
[ ] Plugin validation works
[ ] Rules.md versioning works
[ ] AI-generated packages can be validated
[ ] Offline installed plugins work
[ ] Backup/restore is supported
[ ] Termux upstream update process exists
[ ] Third-party licenses are tracked
[ ] Android distribution requirements are satisfied
```

---

# 89. Important Reality Check

"100% future-proof" cannot literally be guaranteed.

Android changes, Termux changes, package ecosystems change, and Google Play policies can change.

The correct engineering target is therefore:

> **Freeze the interfaces and ownership boundaries, not the implementation.**

The most important interfaces to freeze are:

```text
ForgeRuntime
Plugin Manifest
Plugin Protocol
UI Schema
Permission Model
Job Model
Filesystem Contract
Package Source API
Runtime Health API
```

If those contracts remain stable, the internal implementation can evolve without requiring a fundamental ForgeKit rewrite.

---

# 90. Current Architecture Decision Summary

| Area | Final decision |
|---|---|
| Android | Kotlin |
| UI | Jetpack Compose |
| Runtime | Embedded Termux-derived runtime |
| External Termux required | No |
| Termux capability | Preserve as fully as practical |
| Shell | Full embedded shell |
| Python | Supported |
| Node | Supported |
| Native binaries | Supported |
| Package manager | Termux `apt/pkg` |
| Plugin format | `.forge` |
| Plugin UI | Declarative |
| Plugin protocol | Versioned structured protocol |
| Dependency manager | ForgeKit orchestration + ecosystem managers |
| Plugin permissions | ForgeKit policy layer |
| Android permissions | Android system |
| Plugin storage | ForgeKit-managed |
| Runtime storage | Termux-managed |
| Jobs | ForgeKit |
| Long-running processes | ForgeKit Job Manager + Android lifecycle mechanisms |
| Terminal | Full advanced mode |
| Marketplace | Optional source |
| AI plugins | First-class |
| Rules.md | Versioned development contract |
| Security | Signature + policy + permissions + validation |
| Runtime abstraction | Yes |
| External runtime migration planned | No |
| Termux fork/integration | Maintained and traceable |
| Architecture decisions | ADRs |
| Licensing review | Mandatory before release |

---

**End of Architecture Specification**
