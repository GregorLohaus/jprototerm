# jprototerm

JavaFX canvas terminal prototype using `jlibghostty` for terminal emulation and Nix for
the build environment. It builds a plain JavaFX application (JDK 25, JavaFX 25 via Gradle)
packaged as a Nix derivation — no GraalVM/GluonFX native image.

## Build

```sh
nix build
./result/bin/jprototerm
```

Install it into a profile (works on NixOS and on a plain Debian box with Nix installed):

```sh
nix profile add .
jprototerm
```

The flake bundles everything the app needs — the JDK 25 runtime, the Maven JavaFX modules
and their native libraries, and the gtk/glib/freetype/X11 libraries they load — **except**
the system OpenGL/graphics drivers. `libGL` is supplied by the host at runtime through a GL
shim in the launcher wrapper, so the same closure runs against NixOS, Mesa, or vendor
(e.g. NVIDIA) GPU drivers.

Gradle dependencies are vendored in `deps.json` for the pure Nix sandbox. Regenerate it
after changing dependencies in `build.gradle` (the update script writes `deps.json` in the
current directory):

```sh
$(nix build .#gradleDepsUpdateScript --no-link --print-out-paths)
```

For development:

```sh
nix develop
gradle run
```

The Gradle project is the source of truth for the JavaFX build.

## Config

Configuration is read from:

```text
$XDG_CONFIG_HOME/jprototerm/config.toml
```

If `XDG_CONFIG_HOME` is unset, the fallback is:

```text
$HOME/.config/jprototerm/config.toml
```

If no config file exists, jprototerm writes the default config on startup.

Example, also available in `config.example.toml`:

```toml
[terminal]
columns = 100
rows = 30
shell = "/bin/bash"
font_family = "JetBrainsMono Nerd Font"
font_size = 15

[window]
width = 1200
height = 760

[kitty_graphics]
enabled = true

[scrollback]
editor_command = "vi {file}"

[env.override]
ZELLIJ_SESSION_NAME = ""

[keybindings]
navigate_left = "ALT+H"
navigate_down = "ALT+J"
navigate_up = "ALT+K"
navigate_right = "ALT+L"
toggle_floating = "ALT+F"
new_floating = "ALT+SHIFT+F"
next_floating = "ALT+F12"
close_pane = "ALT+X"
open_font_selector = "ALT+T"
open_scrollback = "ALT+S"
```

## Defaults

- `Alt+h/j/k/l`: navigate panes
- `Alt+f`: show or hide all floating panes
- `Alt+Shift+f`: create a new floating pane
- `Alt+F12`: cycle floating panes
- `Alt+x`: close the active floating pane
- `Alt+t`: open the font selector
- `Alt+s`: open the active pane scrollback in `$EDITOR`
- Font default: `JetBrainsMono Nerd Font`
- Kitty graphics protocol parsing is enabled by default
