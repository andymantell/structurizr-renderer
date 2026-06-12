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

## Deviations from Structurizr

The DSL is parsed with the official Structurizr libraries, so models and views
behave exactly as documented. Rendering, however, deliberately deviates from
the reference renderers in a few places — always in the direction of clearer
diagrams or more deterministic output:

### Layout

- **Declaration order matters.** Graphviz is fed elements and relationships in
  the order they are declared in the DSL. (Stock Structurizr sorts by element
  ID *lexicographically* — `"10"` before `"2"` — so larger models reach the
  layout engine in a semi-arbitrary shuffle.) Since Graphviz seeds its
  within-rank ordering from input order, reordering declarations is an
  implicit, syntax-free way to influence placement. Rank (top-to-bottom
  position) is still determined by relationship direction.
- **Tension relief.** After Graphviz runs, lightly-connected units — loose
  elements, groups, whole top-level deployment nodes — are pulled towards the
  things they connect to and nudged out of overlaps. This counters Graphviz's
  habit of stranding a cluster on the far side of the diagram from its only
  connection (clusters can never interleave in Graphviz).
- **Extra rank separation** (minimum 600) is applied to views containing
  two-way or parallel relationship pairs, so their spread lines and staggered
  labels have room. Node separation is never touched.
- **The canvas crops to the drawn content** plus a small padding, rather than
  using Structurizr paper sizes.

### Rendering

- **Shapes grow to fit their text.** Names and descriptions never overflow or
  get truncated; the element stretches vertically instead (shape-aware: person
  heads, folder tabs, browser chrome and cylinder caps keep their proportions).
- **Unstyled people render as person figures.** Stock Structurizr renders
  everything as a box unless a style says otherwise; here a `Person` element
  defaults to the figure. Any shape declared by a workspace or theme style
  still wins.
- **Lines route around foreign boxes.** A relationship line never cuts through
  an element or boundary frame it isn't connected to — corner detours are
  inserted instead. Two-way and parallel relationship pairs render as separate
  kinked lines with staggered labels rather than overlapping.
- **Labels are placed by a collision engine.** Relationship labels repel each
  other and stay clear of element text, arrowheads (a label can never cover
  one), line crossings, boundary captions, and boundary frame edges (no
  straddling). Stock renderers place labels at a fixed percentage along the
  line and let them collide.
- **A bundled font (Inter, SIL OFL) is used for everything.** Text is measured
  with it, so layout is identical on every platform, and it is embedded into
  each SVG as `@font-face` data URIs — output renders identically everywhere
  with no network access and no dependence on installed fonts.
- **Visual styling differs**: soft drop shadows under elements, white casing
  around lines and arrowheads (so they stay legible crossing dark fills and
  each other), chevron arrowheads, rounded boundary frames, pill-shaped label
  backgrounds, and slightly rounded element corners.

### Not supported

- `routing Orthogonal` falls back to a direct line (`Curved` and `Direct` are
  honoured).
- The `structurizr.boundaryPadding` / `groupPadding` / `deploymentNodePadding`
  view properties influence Graphviz spacing (inherited from the official
  layout) but are not yet read by this renderer's own boundary drawing.

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
