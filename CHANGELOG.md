# Changelog

All notable changes to this project will be documented in this file.

## [1.4.4] - 2026-01-23

### Refactored
- **Configuration Logic Consolidation**: Centralized all configuration parsing, merging, scanning, and loading logic into `ConfigMerger.kt`. This significantly simplifies `RemotePlugin.kt` and `RemotePluginUtils.kt`, making the architecture cleaner and more maintainable.

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
