# Debug Guide for Gradle Remote Plugin

This document lists various commands and tips for debugging and testing the Gradle Remote Plugin, especially for configuration cache compatibility and cross-version testing.

## Prerequisites

- **JDK Version**: Use JDK 17 for Gradle 9.x compatibility.
  ```bash
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.0.2.jdk/Contents/Home
  ```

## Plugin Development

### Compiling the Plugin
To compile only the plugin classes and check for syntax errors:
```bash
./gradlew :remote-plugin:compileKotlin --info
```

### Local Publication
To test changes in sample projects, publish the plugin to your local Maven repository:
```bash
./gradlew :remote-plugin:publishToMavenLocal
```

## Sample Project Testing (Gradle 9.x)

The sample project is located in `consumer-gradle9-sample`. All commands below should be run from that directory or with the `-p` flag.

### Listing All Tasks
Check if remote tasks are correctly registered in subprojects:
```bash
./gradlew :services:marketing-service:tasks --all
```

### Testing a Specific Task
Example: Running the `test_log` task for the `marketing-service` in the `test` profile.
```bash
./gradlew :services:marketing-service:test_log
```

### Configuration Cache Verification
This is the most critical test for Gradle 9 compatibility.
```bash
./gradlew :services:marketing-service:test_log --configuration-cache --info
```

### Debugging Remote Tasks
To see detailed logs including process execution and command wrapping:
```bash
./gradlew :services:marketing-service:test_log --info
```

## Useful Gradle Flags

- `--configuration-cache`: Enable configuration cache and check for serialization issues.
- `--info` / `--debug`: Increase log verbosity.
- `--stacktrace`: Show full stack trace on failure.
- `--no-daemon`: Run without the Gradle daemon (useful if the daemon gets stuck or has stale state).
- `-Xskip-metadata-version-check`: (Internal) Used in `build.gradle` of the plugin to bypass Kotlin version mismatch errors during compilation.

## Configuration Files locations

- **Plugin Configuration**: `gradle/remote-plugin/remote.yml` (in sample projects)
- **Plugin Local Configuration**: `gradle/remote-plugin/remote-local.yml` (can override `remote.yml`)
- **Secrets**: `support-dependencies/configs/secrets/gradle/remote-plugin/secrets.properties`

## Jenkins Debugging
To test Jenkins-related tasks without actual triggers:
```bash
./gradlew :services:marketing-service:test_jenkins_info
```
Ensure `jenkins.url`, `jenkins.user`, and `jenkins.token` are correctly set in `remote.yml` or `remote-local.yml`.
