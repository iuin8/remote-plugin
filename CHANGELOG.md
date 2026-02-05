# Changelog

All notable changes to this project will be documented in this file.

## [2.0.6] - 2026-02-05

### Added
- **Configurable Build Task**: Added support for specifying a custom build task in `remote.yml` via `start.build_task`. Defaults to `bootJar` if not specified.

## [2.0.5] - 2026-02-05

### Changed
- **Task Dependency Refactoring**: Changed the default task dependency for `publish` task from `bootJar` to `build`. This provides better support for a wider range of project types while still ensuring artifacts are generated before publication.

## [2.0.4] - 2026-02-05

### Fixed
- **TTY and Sudo Support**: Added `-tt` and `RequestTTY=force` to `RemotePublishTask` and `RemoteLogTask`. This ensures that remote commands requiring a terminal (like `sudo`) can correctly prompt for passwords and execute in non-interactive environments like Gradle.
- **SSH Command Standardization**: Unified the SSH command internal execution logic across all tasks for better reliability.

## [2.0.3] - 2026-02-05

### Fixed
- **Configuration Cache Regression**: Resolved a critical issue where `Task.project` was accessed at execution time in `RemotePublishTask`. The plugin is now fully compatible with the Gradle Configuration Cache by pre-calculating environment properties and using safe task dependency modeling.
- **Lazy Task Dependency**: Refactored `bootJar` dependency logic to use standard Gradle `TaskCollection` APIs, avoiding execution-time context violations.

## [2.0.2] - 2026-02-05

### Fixed
- **Subproject Path Resolution**: Fixed a critical bug where the `publish` task failed in multi-module projects with nested structures (e.g., `services/module-a`). The plugin now correctly identifies the subproject's own directory instead of assuming it is a direct child of the root project.
- **Service Environment Context**: Unified `SERVICE_DIR` environment variable to use the actual subproject path, ensuring consistency for all remote tasks.

## [2.0.1] - 2026-02-04

### Fixed
- **Interactive Task Stability**: Added `isIgnoreExitValue` to interactive tasks (`arthas`, `debug`, `restart`). This prevents the Gradle build from failing with non-zero exit codes (like 255 or 130) when an interactive session is terminated (e.g., via Ctrl+C) or when a remote connection drops.
- **Arthas Environment Sync**: Standardized `RemoteArthasTask` to use the unified command wrapping logic, ensuring correct user switching (`su - user`) and environment variable loading.

## [2.0.0] - 2026-02-04

### Added
- **Configuration Cache Support**: Refactored the entire plugin to be fully compatible with the Gradle Configuration Cache. All tasks now use lazy `Property` and `MapProperty` for configuration, ensuring significantly faster subsequent builds.
- **Generalized YAML Parser**: Replaced the previous simplified parser with a robust, stack-based YAML parser in `ConfigMerger.kt`. It now supports complex nested structures, list-style environment definitions, and intelligent `service_ports` remapping.
- **Dynamic Task Registration**: Redesigned task registration to be fully dynamic based on the scanned configuration, improving plugin startup performance and reducing coupling.

### Changed
- **Modern Kotlin Standards**: Updated codebase to use modern Kotlin APIs (e.g., `lowercase()`) and improved type safety with reduced compiler warnings.
- **Clean Architecture**: Decoupled task logic from plugin application, resulting in a more maintainable and elegant codebase.
- **Removed Legacy Artifacts**: Cleaned up unused methods (like `envLoad`) and internal diagnostic prints to provide a production-ready console experience.


### Fixed
- **Configuration Deep Merge**: Implemented a deep merge utility to ensure that settings in `remote-local.yml` do not overwrite entire configuration blocks (like `common.base`) from `remote.yml`. This fixes the issue where port mappings and other defaults were lost when local overrides were present.

## [1.4.7] - 2026-01-23

## [1.4.6] - 2026-01-23

## [1.4.5] - 2026-01-23

## [1.4.4] - 2026-01-23

## [1.4.3] - 2026-01-23

## [1.4.2] - 2026-01-19
- **Environment-based Task Grouping**: Tasks are now grouped by environment (e.g., `remote-prod`, `remote-test`) in Gradle and IDEs, preventing task overload in a single group.
- **Production Safety Prompt**: Automatic confirmation prompts for `prod` environments or sensitive tasks.
- **Smart PTY Wrapping**: Automatically uses `script` command to force PTY on Unix-like systems, enabling remote progress bars (like scp/maven) in Gradle.
- **Zero-Config SSH Alias Support**: Enhanced SSH configuration management with project-level isolation.
- **Jenkins Started By Info**: Displays the user who triggered the Jenkins build.

### Changed
- **High Performance Load**: Refactored `envLoad` to be project-level and pre-emptive. `remote.yml` is now loaded exactly once during the `whenReady` phase, eliminating redundant file I/O.
- **Clean Console Output**: Moved initialization, parsing, and background status messages to `DEBUG` level. Only essential progress is shown by default.
- **Global Property Scope**: Environment properties are now stored in `project.extraProperties` for consistent access across all project tasks.

### Fixed
- Redundant `envLoad` calls that caused duplicated log messages.
- Ambiguous file path resolution for SSH configs on certain systems.

---

## [1.3.x] - [Detailed history not tracked in this log]
- Initial remote execution and Jenkins integration features.
- Support for `remote.yml` configuration merging.
