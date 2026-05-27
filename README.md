# jprototerm

JavaFX canvas terminal prototype using `jlibghostty` for terminal emulation, Nix for the build environment, and GluonFX/GraalVM Native Image for the Linux binary.

## Build

```sh
nix build
```

For development:

```sh
nix develop
gradle -PjlibghosttyMavenRepo="$JLIBGHOSTTY_MAVEN_REPO" run
gradle -PjlibghosttyMavenRepo="$JLIBGHOSTTY_MAVEN_REPO" nativeCompile
```

The current flake follows the normal Gradle dependency-resolution shape. For a fully pure Nix build, vendor the Gradle dependency graph with `gradle2nix` or a checked-in Maven repository.

## Config

Configuration is read from:

```text
$XDG_CONFIG_HOME/jprototerm/config.toml
```

If `XDG_CONFIG_HOME` is unset, the fallback is:

```text
$HOME/.config/jprototerm/config.toml
```

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

[keybindings]
navigate_left = "ALT+H"
navigate_down = "ALT+J"
navigate_up = "ALT+K"
navigate_right = "ALT+L"
toggle_floating = "ALT+F"
```

## Defaults

- `Alt+h/j/k/l`: navigate panes
- `Alt+f`: open or close a floating pane
- Font default: `Symbols Nerd Font Mono`
- Kitty graphics protocol parsing is enabled by default
