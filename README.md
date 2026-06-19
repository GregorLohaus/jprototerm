# jprototerm

JavaFX canvas terminal prototype using `jlibghostty` for terminal emulation and Nix for
the build environment. It builds a plain JavaFX application (JDK 25, JavaFX 25 via Gradle)
packaged as a Nix derivation — no GraalVM/GluonFX native image. It supports tiled and
floating panes and tabs.

> [!CAUTION]
> `nix profile add` has only been tested on **Debian with the proprietary NVIDIA driver**.
> The runtime GL shim hardcodes Debian's `/lib/x86_64-linux-gnu` driver paths and selects
> the NVIDIA GLX/EGL vendor, so it likely won't work yet on other distros, Wayland-only
> setups, or Mesa/AMD/Intel GPUs. I'm happy to accept pull requests that broaden host
> support.

<video src="https://gitea.gregorlohaus.com/gregor/jprototerm/media/branch/main/demo.mp4" controls></video>

## Build

```sh
nix build
./result/bin/jprototerm
```

Install it into a profile (see the caution above on host support):

```sh
nix profile add .
jprototerm
```

Or install straight from the remote — note the `git+https://` scheme (a bare `https://`
URL is treated as a tarball, not a git repo):

```sh
nix profile add git+https://gitea.gregorlohaus.com/gregor/jprototerm
```

Add `?ref=<branch-or-tag>` to pin a revision. The target machine needs Nix with the
`nix-command` and `flakes` features enabled and network access — the build fetches the
`jlibghostty`/`ghostty` flake inputs plus the JDK and Gradle from the binary caches.

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

## Daemon (optional, faster launches)

Cold start pays for JVM + JavaFX + GL/X11 init every time. The optional daemon keeps one JVM
(one toolkit) running and hosts every window in it, so a `jprototerm` launch just asks the
daemon to open a window — it appears without paying that startup cost again.

Run it once in the background:

```sh
jprototerm --daemon &
```

After that, a bare `jprototerm` connects to the daemon and opens a window in the current
directory. If no daemon is running, `jprototerm` falls back to a standalone in-process window
(today's behavior), so it always works.

For development testing, use `jprototerm --standalone` to skip the daemon even when one is
running.

To start the daemon automatically with your graphical session, enable the bundled **user**
service (it's a user service, not a system one, because X11 needs a display — which only
exists after you log in):

```sh
mkdir -p ~/.config/systemd/user
ln -sf "$(dirname "$(readlink -f "$(command -v jprototerm)")")/../share/systemd/user/jprototerm.service" \
  ~/.config/systemd/user/jprototerm.service
systemctl --user enable --now jprototerm.service
```
After upgrading via nix profile upgrade: 
```sh
systemctl --user disable jprototerm
ln -sf "$(dirname "$(readlink -f "$(command -v jprototerm)")")/../share/systemd/user/jprototerm.service" \
  ~/.config/systemd/user/jprototerm.service
systemctl --user enable --now jprototerm.service
systemctl --user restart jprototerm.service
```

If the daemon can't reach your display (e.g. `systemctl --user status jprototerm` shows it
failing to open a window), import the session variables once and restart it:

```sh
systemctl --user import-environment DISPLAY XAUTHORITY
systemctl --user restart jprototerm.service
```

Closing a window (the WM close button, or the close-pane key on the last pane) tears that
window down — its shell processes are signalled with the configured `close_signal` — without
affecting other windows or the daemon. Stop the daemon (and all its windows) with
`systemctl --user stop jprototerm.service`, or `pkill -f 'jprototerm --daemon'`.

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

[worktree]
relative_worktree_path = "./.worktrees"

[env.override]
ZELLIJ_SESSION_NAME = ""

[keybindings]
navigate_left = "ALT+H"
navigate_down = "ALT+J"
navigate_up = "ALT+K"
navigate_right = "ALT+L"
toggle_floating = "ALT+F"
new_pane = "ALT+N"
next_floating = "ALT+F12"
close_pane = "ALT+X"
new_tab = "ALT+A"
previous_tab = "ALT+SHIFT+H"
next_tab = "ALT+SHIFT+L"
open_font_selector = "ALT+T"
open_scrollback = "ALT+S"
create_worktree = "ALT+W"
pane_sync_select = "ALT+Y"
pane_sync_commit = "ALT+SHIFT+Y"
pane_sync_end = "ALT+U"
paste = "CTRL+SHIFT+V"
```

## Defaults

- `Alt+h/j/k/l`: navigate panes
- `Alt+n`: new pane — a floating pane when floating panes are shown, otherwise a new tiled
  pane (tiled panes are split equally across the width)
- `Alt+f`: show or hide all floating panes
- `Alt+F12`: cycle floating panes
- `Alt+x`: close the active pane; closing a tab's last pane closes the tab, and closing the
  last pane of the last tab quits
- `Alt+a`: new tab
- `Alt+Shift+h` / `Alt+Shift+l`: previous / next tab
- `Alt+t`: open the font selector
- `Alt+s`: open the active pane scrollback in `$EDITOR`
- `Alt+w`: edit a worktree name, then run `git worktree add <relative_worktree_path>/<name>`
  from the previously focused pane's working directory
- `Alt+y`: enter pane-sync selection mode and toggle the focused pane in the sync set
- `Alt+Shift+y`: commit the current pane-sync selection; input typed or pasted into any synced
  pane is mirrored to the other synced panes
- `Alt+u`: end pane sync
- `Ctrl+Shift+v`: paste
- Font default: `JetBrainsMono Nerd Font`
- Kitty graphics protocol parsing is enabled by default

Each tab has its own stack of tiled and floating panes; only the active tab is rendered. A
thin tab bar appears at the top when more than one tab is open. Closing the last tiled pane
while floating panes exist promotes the most recently active floating pane to a tiled pane.
