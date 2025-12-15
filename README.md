# Remote Exec Plugin

`io.github.iuin8.remote` is a Gradle plugin designed to simplify deployment pipelines by executing local and remote shell commands.

## Features
- **Remote Execution**: Execute shell commands on remote servers via SSH.
- **Local Execution**: Run local shell scripts as part of your build process.
- **Jenkins Integration**: Easily integrate with Jenkins for automated deployments.
- **Configuration Management**: centralized configuration for ports, logs, and services via `remote.yml`.

## Installation

Using the plugins DSL:

```groovy
plugins {
  id "io.github.iuin8.remote" version "0.1.37"
}
```

Using legacy plugin application:

```groovy
buildscript {
  repositories {
    maven {
      url "https://plugins.gradle.org/m2/"
    }
  }
  dependencies {
    classpath "io.github.iuin8:remote-plugin:0.1.37"
  }
}

apply plugin: "io.github.iuin8.remote"
```

## Basic Usage

### Configuration

Define your configuration in a `remote.yml` file. You can find examples here:

- [Example Project](https://github.com/iuin8/remote-plugin/blob/main/consumer-gradle6-sample)
- [Example Configuration File](https://github.com/iuin8/remote-plugin/blob/main/consumer-gradle6-sample/gradle/remote-plugin/remote.yml)

### Usage

(See below for detailed configuration in Chinese, or refer to the source code for property definitions)

[Chinese Documentation / 中文文档](README_CN.md)
