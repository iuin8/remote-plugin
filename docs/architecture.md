# Architecture: Portable SSH Configuration

## Concept: Shadow Resolved Config

To support portable SSH configurations while maintaining standard SSH tool compatibility, the plugin uses a "Shadow Resolved Config" mechanism.

### Data Flow

```mermaid
graph TD
    A["Project: gradle/remote-plugin/.ssh/config.template"] -- "Contains ${RP_PROJECT_ROOT_PATH}" --> B["Plugin: SshConfigManager"]
    C["Project Root Path"] --> B
    B -- "Resolves Placeholders" --> D["Local Copy: ~/.ssh/gradle/remote-plugin/projects/..."]
    D -- "Absolute Paths" --> E["Aggregate Config: ~/.ssh/gradle/remote-plugin/config"]
    E -- "Included In" --> F["System Config: ~/.ssh/config"]
    G["Terminal 'ssh' / Gradle 'scp'"] --> F
```

### Components

#### 1. Portable Template (`config.template`)
- Committed to Git.
- Uses `${RP_PROJECT_ROOT_PATH}` for any path involving the project root (e.g., `IdentityFile`).
- Ensures that the same file works for different developers with different checkout paths.

#### 2. Resolution Mechanism
- Triggered on plugin application.
- Extracts the absolute path of `project.rootDir`.
- Generates a local, resolved version of the config.
- Files are named with a hash of the project's absolute path to avoid collisions between projects with the same name.

#### 3. Integration Logic
- **Primary Include**: Injects `Include ~/.ssh/gradle/remote-plugin/config` into the very top of `~/.ssh/config`.
- **Secondary Include**: The aggregate file `~/.ssh/gradle/remote-plugin/config` includes all the individual project resolved configs.
- **Environment Injection**: Tasks (like `publish`) also receive `RP_PROJECT_ROOT_PATH` to handle cases where tools might try to resolve the variable themselves from the environment.

## Security & Maintainability

- **Permissions**: Automatically sets `700` for directories and `600` for config/key files.
- **Atomicity**: Uses move-to-replace strategy with `.tmp` files to prevent configuration corruption during writes.
- **Automation**: One-time setup; works "unfelt" after the first run.

## Performance Architecture (v2.0.0+)

### 1. Configuration Cache Compatibility
The 2.0.0 refactor transitioned all configurations to Gradle's **Configuration Cache** friendly APIs.
- **Lazy Resolution**: All task properties use `Property<T>` or `MapProperty<K, V>`.
- **Pre-emptive Parsing**: Configuration is parsed during the configuration phase, but its values are only computed on-demand during execution, allowing Gradle to serialize the task state without storing live references to the `Project` object.

### 2. Generalized YAML Parser
`ConfigMerger` employs a recursive stack-based parser to handle multi-level inheritance and overrides.
- **Service Port Remapping**: Automatically scans any configuration level for `service_ports` and remaps found entries to the top-level namespace (e.g., `service_ports.submodule_name`).
- **Environment Lists**: Supports defining multiple environments in a list-style YAML format, simplifying large scale configuration files.

### 3. Stability & Interaction (v2.0.1+)
Interactive tasks (`Arthas`, `Debug`, `Restart`) are designed to be "exit-safe":
- **Ignore Exit Value**: Tasks are configured to ignore non-zero exit codes from SSH sessions, ensuring that intentional termination by the user does not trigger a Gradle build failure.
- **Standardized Wrapping**: All interactive commands use a unified `wrapRemoteCommand` logic to ensure consistent PTY handling and environment context (`su - user`, `bash -lc`).
