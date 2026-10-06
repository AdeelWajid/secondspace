# Contributing to NEXTVM

Thank you for your interest in contributing to NEXTVM! This project aims to build the most capable Android virtual environment engine, and every contribution matters.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Project Architecture](#project-architecture)
- [Making Changes](#making-changes)
- [Code Style](#code-style)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Areas Where Help is Needed](#areas-where-help-is-needed)
- [Reporting Issues](#reporting-issues)

---

## Getting Started

1. **Fork** the repository on GitHub
2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/NEXTVM.git
   cd NEXTVM
   ```
3. **Create a branch** for your changes:
   ```bash
   git checkout -b feature/your-feature-name
   ```

## Development Setup

### Prerequisites

| Tool | Version | Required |
|------|---------|----------|
| Android Studio | Ladybug 2024.2.1+ | Yes |
| JDK | 17 | Yes |
| Android SDK | API 35 | Yes |
| NDK | 27.1.12297006 | For native hook module |
| CMake | 3.22+ | For native hook module |

### Build Steps

```bash
# Build the project
./gradlew :app:assembleDebug

# Run lint checks
./gradlew lint

# Build all modules
./gradlew build
```

### Testing on Device

NEXTVM requires a real Android device for proper testing (API 26+). The virtualization engine relies on system-level features that don't work correctly in the Android Emulator.

## Project Architecture

NEXTVM is a 15-module Gradle project following Clean Architecture principles:

```
app/                    Entry point, Hilt DI, stub components
core/
  virtualization/       VM engine, process management (START HERE)
  hook/                 Native (C++) and Java hooking engine
  binder/               Binder proxy interception layer
  sandbox/              File system isolation
  services/             Virtual system services, GMS integration
  framework/            Android framework parsing
  apk/                  APK parsing and class loading
  model/                Data models
  common/               Shared utilities
  designsystem/         Material 3 theme
feature/
  launcher/             App grid UI
  appmanager/           APK install/uninstall
  settings/             Engine configuration
  filemanager/          Virtual file browser
```

### Key Files to Understand First

If you're new to the codebase, read these files in order:

1. `core/model/VirtualApp.kt` — The core data model
2. `core/model/ProcessSlot.kt` — How process slots work
3. `core/virtualization/engine/VirtualEngine.kt` — The main engine
4. `core/virtualization/engine/ActivityThreadHook.kt` — How Activity launches are intercepted
5. `core/binder/proxy/ActivityManagerProxy.kt` — How system calls are redirected
6. `core/sandbox/VirtualContext.kt` — How file I/O is sandboxed
7. `core/hook/src/main/cpp/native-hook.cpp` — The C++ native hook engine

## Making Changes

### Before You Start

- Check the [Issues](https://github.com/AdeelWajid/wajid-nextvm/issues) page for existing discussions
- For large changes, open an issue first to discuss the approach
- Make sure your change aligns with the project's goals (no-root virtualization)

### Code Guidelines

- **Kotlin** for all new Android code
- **C++** for native hooks (in `core/hook/src/main/cpp/`)
- Follow existing code patterns in the module you're modifying
- Keep methods focused — one method, one responsibility
- Add Timber logging for important operations: `Timber.d("VirtualEngine: launching %s", packageName)`

### What NOT to Do

- Don't add features that require root access
- Don't include proprietary/copyrighted SDKs
- Don't add tracking, analytics, or telemetry
- Don't commit IDE-specific files (`.idea/`, `*.iml`)
- Don't commit API keys, tokens, or credentials

## Code Style

- **Kotlin**: Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **C++**: Follow Android NDK conventions with consistent naming
- **Indentation**: 4 spaces (no tabs)
- **Line length**: 120 characters max
- **Naming**:
  - Classes: `PascalCase` (e.g., `VirtualEngine`)
  - Functions: `camelCase` (e.g., `installApp`)
  - Constants: `SCREAMING_SNAKE_CASE` (e.g., `MAX_PROCESS_SLOTS`)
  - Private fields: `camelCase` with underscore prefix for backing fields

## Submitting a Pull Request

1. **Ensure your code compiles** without errors
2. **Update documentation** if your change affects public APIs or architecture
3. **Write a clear PR description**:
   - What does this change do?
   - Why is this change needed?
   - How was it tested?
4. **Keep PRs focused** — one feature or fix per PR
5. **Reference related issues** using `Fixes #123` or `Relates to #456`

### PR Template

```markdown
## What Changed
Brief description of the changes.

## Why
Explanation of why this change is needed.

## Testing
How this was tested (device, API level, specific apps tested).

## Screenshots (if UI changes)
Before/After screenshots.
```

## Areas Where Help is Needed

### Good First Issues

- Writing unit tests (currently at 0% coverage)
- Adding KDoc comments to public APIs
- Improving error messages and user-facing strings
- UI animations and transitions in feature modules

### Intermediate

- System service proxy implementations (Camera, Storage/SAF, DownloadManager)
- WebView process virtual context support
- Clipboard, alarm, job scheduler persistence across VM restarts
- Performance profiling and optimization

### Advanced

- Native hook engine improvements (migrating to [bhook](https://github.com/nicklhw/bhook))
- Multi-process ClassLoader isolation for stable app hosting
- App compatibility fixes for specific frameworks (Flutter, React Native, Unity)
- Play Integrity / SafetyNet bypass improvements
- Memory management optimization for multiple running instances

### Research

- Investigating ARM64 hooking libraries for broader native function interception
- Android 15/16 compatibility analysis for new security restrictions
- GMS integration improvements for Google Sign-In flow

## Reporting Issues

When reporting a bug, include:

- **Device**: Model, Android version, RAM
- **App being virtualized**: Package name and version
- **Steps to reproduce**: Numbered steps
- **Expected behavior**: What should happen
- **Actual behavior**: What actually happens
- **Logs**: Logcat output filtered to `com.nextvm` (if possible)

### Issue Labels

| Label | Description |
|-------|-------------|
| `bug` | Something isn't working |
| `enhancement` | New feature or improvement |
| `good-first-issue` | Good for newcomers |
| `help-wanted` | Extra attention needed |
| `compatibility` | App compatibility issue |
| `native` | Related to C++/NDK code |
| `proxy` | Related to Binder proxy layer |

---

## Questions?

If you have questions about the codebase or contribution process, open a [Discussion](https://github.com/AdeelWajid/wajid-nextvm/discussions) on GitHub.

Thank you for contributing to NEXTVM!
