# Changelog

All notable changes to this project will be documented in this file.

## [1.4.9] - 2026-02-13

### Changed
- **SSH Config Optimization**: Refined the automatic SSH configuration to use the specific path `~/.ssh/gradle/remote-plugin/config` instead of a wildcard. This prevents SSH from scanning subdirectories (like `projects/`) and improves stability by avoiding potential directory scanning errors.

## [1.4.8] - 2026-01-23

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
