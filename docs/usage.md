# Usage Guide: Portable SSH Configuration

This plugin allows you to share SSH configurations across different projects and machines without manual environment variable setup.

## 1. Create a Template

Create a file at `gradle/remote-plugin/.ssh/config.template` in your project root.

```sshconfig
Host my-remote-server
    HostName 1.2.3.4
    User deploy
    # Use ${RP_PROJECT_ROOT_PATH} for paths within the project
    IdentityFile ${RP_PROJECT_ROOT_PATH}/gradle/remote-plugin/.ssh/id_ed25519
```

## 2. Automate Setup

Run any Gradle task:

```bash
./gradlew tasks
```

The plugin will:
- Detect the `.ssh/` directory.
- Generate a local resolved version of the config.
- Automatically link it to your system `~/.ssh/config`.

## 3. Use Anywhere

### From Gradle
All remote tasks (publish, debug, etc.) will automatically use the configuration defined in your template.

### From Terminal
You can use the server alias directly in your terminal:

```bash
ssh my-remote-server
```

## 4. Portability

When another developer clones the project into a different path, they simply run `./gradlew tasks`, and the plugin will regenerate the local resolved config with their specific absolute path. There is no need for them to manually set any environment variables.
