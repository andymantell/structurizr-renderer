# structurizr-renderer

A standalone CLI tool that renders [Structurizr DSL](https://github.com/structurizr/dsl) workspace files to SVG or PNG diagrams.

## Requirements

- Java 21+
- [Graphviz](https://graphviz.org/download/) (`dot` must be on your `PATH`) — used for automatic layout

## Installation

Download the latest fat JAR from the [Releases](../../releases) page.

## Usage

```
java -jar structurizr-renderer.jar [OPTIONS] <path/to/workspace.dsl>
```

### Options

| Option | Default | Description |
|---|---|---|
| `-o`, `--output` `<dir>` | Same directory as the DSL file | Output directory for rendered files |
| `-f`, `--format` `<fmt>` | `svg` | Output format: `svg`, `png`, or `both` |
| `-v`, `--view` `<key>` | _(all views)_ | Render only the view with this key |
| `--no-autolayout` | — | Skip automatic layout even when the DSL requests it |
| `--proxy` `<host:port>` | _(direct connection)_ | HTTP(S) proxy used when downloading non-bundled themes or icons (bundled themes never touch the network) |
| `--png-width` `<px>` | _(SVG's natural size)_ | Width in pixels of PNG output, preserving aspect ratio |
| `--verbose` | — | Print full stack traces on errors |
| `-h`, `--help` | — | Show help and exit |
| `-V`, `--version` | — | Print version and exit |

### Examples

Render all views to SVG alongside the DSL file:
```
java -jar structurizr-renderer.jar workspace.dsl
```

Render all views to PNG in a specific output directory:
```
java -jar structurizr-renderer.jar -f png -o diagrams/ workspace.dsl
```

Render a single named view to both SVG and PNG:
```
java -jar structurizr-renderer.jar -f both -v SystemContext workspace.dsl
```

## Building from source

```
./mvnw package -DskipTests
```

The fat JAR is produced at `target/structurizr-renderer-<version>.jar`.

## Releasing

Push a version tag to trigger the GitHub Actions release pipeline, which builds the JAR and attaches it to a GitHub Release:

```
git tag v1.0.0
git push origin v1.0.0
```
