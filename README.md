<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" width="96" alt="Frost logo">
</p>

<h1 align="center">Frost</h1>

<p align="center">
  <b>Frosted glass for your JetBrains IDE.</b><br>
  Translucent, blurred IDE windows.
</p>

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#settings">Settings</a> ·
  <a href="#faq">FAQ</a> ·
  <a href="#how-it-works">How it works</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-macOS-lightgrey" alt="macOS">
  <img src="https://img.shields.io/badge/JetBrains%20IDEs-2026.2%2B-blue" alt="JetBrains IDEs 2026.2+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license"></a>
  <img src="https://img.shields.io/badge/status-early%20access-orange" alt="Early access">
  <a href="https://github.com/pallad42/frost-jetbrains/actions/workflows/build.yml"><img src="https://github.com/pallad42/frost-jetbrains/actions/workflows/build.yml/badge.svg" alt="Build"></a>
</p>

<p align="center">
  <b>Early access:</b> tested on macOS with IntelliJ IDEA 2026.2; other JetBrains IDEs 2026.2+ should work too.
  Something looks off or stays solid? Please <a href="https://github.com/pallad42/frost-jetbrains/issues">open an issue</a>.
</p>

<p align="center">
  <img src="docs/frost-editor-dark.png" width="900" alt="JetBrains IDE with Frost: translucent, blurred editor, project tree, terminal and documentation popup over the desktop">
</p>
<p align="center">
  <sub>Wallpaper: <a href="https://unsplash.com/photos/aerial-photography-of-body-of-water-surrounded-by-trees-p8hnTQOyzfU">aerial photography of a body of water surrounded by trees</a> by Jeremy Bishop on Unsplash</sub>
</p>

<!-- Demo recording goes here:
<p align="center">
  <img src="docs/demo.gif" width="900" alt="Frost: plain IDE turning into frosted glass">
</p>
-->

## Features

<!-- Plugin description -->
Translucent, blurred IDE windows - frosted glass for your JetBrains IDE. A transparent background with real blur,
without a background image.

- **The whole IDE turns to glass** - main window, editor, tool windows, terminal, toolbars, status bar, popups,
  tooltips, notifications and dialogs.
- **Real blur, rendered by macOS.** The window server blurs whatever is behind the IDE (frosted glass, acrylic-like),
  so it stays light on resources.
- **The IDE paints exactly as designed.** Frost does not change a single colour or component: only the theme's
  background colours turn to glass, applied to the finished pixels of each window. Text, icons, selection, file
  colours, search results and terminal output stay as they are; popups, menus and dialogs stay solid enough to read.
- **Works with everything.** Because the effect works on the final image, it does not matter how something was drawn:
  editors, tool windows, tables, the Markdown preview (an embedded browser), other plugins' UI and animations all get
  the same glass.
- **Light and dark themes.** Works with both, including switching theme while the IDE is running.
- **Tune it live.** UI opacity, floating windows opacity and blur sliders with instant preview, Fast or Exact
  rendering, and Reset to Defaults.

Settings: **Settings | Appearance & Behavior | Frost**. Quick toggle: **View | Frost**.

**macOS only for now** - on Windows and Linux the plugin does nothing.

**Early access:** tested on macOS with IntelliJ IDEA 2026.2; other JetBrains IDEs 2026.2+ (PyCharm, WebStorm,
PhpStorm, GoLand...) should work too. Something looks off or stays solid? Please report it on
[GitHub Issues](https://github.com/pallad42/frost-jetbrains/issues).
<!-- Plugin description end -->

## Install

**From JetBrains Marketplace:** <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>, search for **Frost**,
Install and restart the IDE.

**From disk:**

1. Download `frost-<version>.zip` from [Releases](https://github.com/pallad42/frost-jetbrains/releases), or build it
   with `./gradlew buildPlugin`.
2. <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install Plugin from Disk…</kbd>
3. Restart the IDE.

Quick toggle at any time: <kbd>View</kbd> → <kbd>Frost</kbd>.

## Settings

<kbd>Settings</kbd> → <kbd>Appearance & Behavior</kbd> → <kbd>Frost</kbd>. Every change previews live; Cancel reverts.

<p align="center">
  <img src="docs/frost-settings.png" width="640" alt="Frost settings page: opacity and blur sliders for the main and floating windows, Rendering (Fast or Exact) and Reset to Defaults">
  <br>
  <sub>The Frost settings page</sub>
</p>

| Setting | Default | What it does |
|---|---|---|
| UI opacity | 85% | How solid the background of the main window stays |
| Floating windows opacity | 95% | The same for popups, menus, tooltips, dialogs, Settings |
| Blur radius | 5 | Blur of what is behind the main window, 0 = off |
| Floating windows blur radius | same as Blur radius | Blur behind popups, dialogs, Settings, 0 = off |
| Rendering | Fast | **Fast**: Core Animation's colour matrix, several times lighter for macOS (a private macOS filter). **Exact**: public Core Image with exact colours, noticeably heavier when scrolling |

Notifications, balloons and hints drawn inside the main window are part of it and follow UI opacity.

## FAQ

<details>
<summary><b>Does it work on Windows or Linux?</b></summary>

Not yet; on other systems the plugin does nothing. Every part of the effect is a macOS feature: the colour filter
runs in Core Animation, macOS's window compositor, and the blur is a window-server call. Windows (DWM) and Linux
compositors offer blur behind a window (Acrylic/Mica, KDE blur), but none of them lets an application put a filter on
its own finished window image, so the same approach would need a different place to do the colour-to-alpha step.
</details>

<details>
<summary><b>The IDE looks too transparent.</b></summary>

Raise <b>UI opacity</b> (or <b>Floating windows opacity</b> for popups and dialogs), or the blur radius.
</details>

<details>
<summary><b>A picture or icon has a see-through spot.</b></summary>

Frost turns pixels into glass by their colour: a pixel of an image that has exactly the colour of a theme background
becomes glass too. In practice this is rare and small.
</details>

<details>
<summary><b>Scrolling feels slightly slower on a big maximised window.</b></summary>

A translucent window has to be composited by macOS together with everything behind it, on every frame, with the
colour filter applied; the cost grows with the window size and the blur. Keep <b>Rendering</b> on <b>Fast</b>;
setting <b>Blur radius</b> to 0 helps the most after that.
</details>

<details>
<summary><b>What changes when upgrading from 0.1.x?</b></summary>

The editor, sticky lines and highlight opacity sliders are gone: the editor follows UI opacity and highlights stay as
the theme paints them. Frost 0.1.x switched a few IDE animations and caches off while it ran and the IDE kept them off
afterwards; 0.2.0 turns them back on once, on its first start.
</details>

## How it works

<details>
<summary>The details, for the curious</summary>

- The IDE keeps painting itself fully opaque; Frost does not touch Swing, the theme, editor colour schemes or any
  component.
- Each window's content layer (the Metal layer JBR renders Swing into) gets a filter that Core Animation runs on the
  window's finished pixels. It "un-mixes" every pixel from the theme background colour (colour-to-alpha): a pixel of
  that colour becomes glass with the opacity from the settings, a pixel far from it stays solid, and everything in
  between (antialiased text, hover and selection shades) keeps its contrast to the background.
- **Fast** rendering (the default) uses Core Animation's own colour matrix filter, which the window server runs
  several times cheaper than a Core Image filter. Being linear, it measures how far a pixel is from the background by
  the average of its channels; that only changes how see-through coloured pixels are, not their colour.
- **Exact** rendering uses an exact 64³ colour table through Core Image (`CIColorCubeWithColorSpace`), a public API,
  at a higher cost for the window server. Each one is the other's fallback where it is not available.
- The window and its layer are made non-opaque and the window server blurs what is behind it
  (`CGSSetWindowBackgroundBlurRadius`).
- The colour table is rebuilt when a slider moves or the theme changes; native state is re-sent only when the live
  layer lost it (JBR may recreate the layer on resize, display change or project switch).
</details>

## Build

```
./gradlew buildPlugin      # build/distributions/frost-<version>.zip
./gradlew runIde           # sandbox IDE with the plugin
./gradlew installPlugin    # install into your IntelliJ IDEA (close it first; see ideaConfigDir)
./gradlew verifyPlugin     # compatibility check (Plugin Verifier)
```

Builds against the IntelliJ IDEA at `ideaPath` in `gradle.properties` when it exists, otherwise downloads
`platformVersion` (that is what CI does). Gradle provisions JDK 21 automatically.

`./gradlew runIde -Pfrost.selftest` shows every kind of overlay and switches between light and dark theme, for a
visual check before a release. `./gradlew runIde -Pfrost.driver=<dir>` executes commands written to `<dir>/cmd.txt`
(open a file, show a context menu, hover its items, take a screenshot of the IDE's windows) - see `FrostDriver`.

### Releasing

1. Bump `version` in `build.gradle.kts` and the change notes in `plugin.xml`, push. The Marketplace description is
   built from the part of this README between the `Plugin description` markers.
2. Publish a GitHub release tagged `v<version>`. CI builds and verifies the plugin, attaches the zip to the release
   and uploads it to JetBrains Marketplace (needs the `PUBLISH_TOKEN` repository secret).

## Contributing

Issues and pull requests are welcome. Found a part of the UI that stays solid or something that looks off
with your theme? Open an issue - a screenshot or a short screen recording helps a lot. Windows and Linux support
would be a great contribution too.

## Credits

Built with the help of Claude (Anthropic) - design, testing and the flicker hunting were a joint effort.

## License

MIT - see [LICENSE](LICENSE).
