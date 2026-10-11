# Frost

Translucent, blurred windows for JetBrains IDEs - frosted glass for your IDE, like `background-opacity` /
`background-blur` in Ghostty.

- The whole UI is translucent: main window, tool windows, toolbars, status bar, popups, tooltips, dialogs.
- Real window blur rendered by the system compositor, so it stays light on resources.
- Coloured backgrounds (selection, current line, file colours, search results) stay visible, just translucent.
- No flicker: works around the JDK/JBR repaint issues of translucent windows (see below).

**macOS only for now.** On Windows and Linux the plugin does nothing.

## Settings

**Settings → Appearance & Behavior → Frost** (all changes preview live, **Reset to Defaults** included):

| Setting | Default | What it does |
|---|---|---|
| UI opacity | 85% | Tint of the main window |
| Floating windows opacity | 95% | Popups, tooltips, notifications, dialogs, Settings |
| Editor opacity | 0% | Extra editor background on top of the tint |
| Sticky lines opacity | 85% | Background of sticky lines over scrolling code |
| Blur radius | 5 | Blur of what is behind the window, 0 = off |
| Highlight opacity | 70% | Strength of coloured backgrounds |

Quick toggle: **View → Frost**.

Turn off **Appearance → Background Image** while using Frost - the two effects fight each other.

## Build

    ./gradlew buildPlugin      # build/distributions/frost-<version>.zip
    ./gradlew runIde           # sandbox IDE with the plugin
    ./gradlew installPlugin    # install into the IDE set by ideaConfigDir (close the IDE first)
    ./gradlew verifyPlugin     # compatibility check

Requires the local IDE path in `gradle.properties` (`ideaPath`); Gradle provisions JDK 21 automatically.

## How it works

- The window peer is made per-pixel translucent; the native NSWindow gets a single tint on its Metal layer and a
  window-server blur (`CGSSetWindowBackgroundBlurRadius`, the same call Ghostty uses).
- Every background colour (theme, components, editor schemes) is replaced by a translucent version: neutral
  surfaces become fully transparent (stacked translucent fills would pulse), coloured ones keep their hue.
- Flicker fixes: `Window.paint` would wipe the whole window first (JDK-8303950) - the window keeps reporting
  itself opaque to Swing while its peer stays translucent; theme-change bursts are coalesced; native state is only
  re-sent when it really changed; loading-layer snapshots without alpha are skipped.

## Credits

Built with the help of Claude (Anthropic) - design, testing and the flicker hunting were a joint effort.

## License

MIT - see [LICENSE](LICENSE).
