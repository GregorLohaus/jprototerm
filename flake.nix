{
  description = "jprototerm";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jlibghostty.url = "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";
    ghostty.follows = "jlibghostty/ghostty";
  };

  outputs = { self, nixpkgs, jlibghostty, ghostty }:
    let
      supportedSystems = [ "x86_64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;

      # Everything the JavaFX natives (and jlibghostty) dlopen at runtime, EXCEPT the
      # system OpenGL/graphics drivers. libGL is intentionally left out: it is supplied
      # by the host at runtime via the GL shim in the wrapper below, so the same closure
      # works on NixOS and on a plain Debian box with vendor GPU drivers installed.
      runtimeLibsFor = pkgs: ghosttyVt: [
        pkgs.glib
        pkgs.gtk3
        pkgs.pango
        pkgs.cairo
        pkgs.gdk-pixbuf
        pkgs.harfbuzz
        pkgs.freetype
        pkgs.fontconfig.lib
        pkgs.libx11
        pkgs.libxext
        pkgs.libxrender
        pkgs.libxtst
        pkgs.libxi
        pkgs.libxcursor
        pkgs.libxrandr
        pkgs.libxinerama
        pkgs.libxcb
        pkgs.libxxf86vm
        pkgs.zlib
        ghosttyVt
      ];
    in {
      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };

          jlib = jlibghostty.packages.${system}.jlibghostty;
          ghosttyVt = ghostty.packages.${system}.libghostty-vt;

          runtimeLibs = runtimeLibsFor pkgs ghosttyVt;

          jprototerm = pkgs.stdenv.mkDerivation (finalAttrs: {
            pname = "jprototerm";
            version = "0.1.0";
            src = ./.;

            nativeBuildInputs = [
              pkgs.jdk25
              pkgs.gradle_9
              pkgs.makeWrapper
            ];

            buildInputs = runtimeLibs;

            mitmCache = pkgs.gradle_9.fetchDeps {
              pkg = finalAttrs.finalPackage;
              data = ./deps.json;
              useBwrap = false;
            };

            # Builds build/install/jprototerm/{bin,lib} with every runtime jar, including
            # the maven javafx-*-linux jars that carry the platform natives.
            gradleBuildTask = "installDist";
            gradleFlags = [
              "--no-build-cache"
              "--stacktrace"
              "-Dorg.gradle.java.home=${pkgs.jdk25}"
            ];

            JAVA_HOME = "${pkgs.jdk25}";
            JLIBGHOSTTY_MAVEN_REPO = "${jlib}/maven";

            preBuild = ''
              export HOME="$TMPDIR/home"
              export GRADLE_OPTS="-Duser.home=$HOME ''${GRADLE_OPTS:-}"
            '';

            preGradleUpdate = ''
              export HOME="$TMPDIR/home"
            '';

            installPhase = ''
              runHook preInstall

              mkdir -p "$out/share/jprototerm"
              cp -a build/install/jprototerm/lib "$out/share/jprototerm/lib"

              # JavaFX is a set of proper modular jars: put them on the module path and
              # keep the application + plain dependency jars on the classpath, so the two
              # worlds do not collide.
              mkdir -p "$out/share/jprototerm/javafx"
              mv "$out/share/jprototerm/lib"/javafx-*.jar "$out/share/jprototerm/javafx/"

              # Build an explicit colon-separated classpath. A "lib/*" glob would be
              # expanded by the wrapper's shell before java sees it, breaking -cp.
              classpath=""
              for jar in "$out"/share/jprototerm/lib/*.jar; do
                classpath="$classpath''${classpath:+:}$jar"
              done

              makeWrapper "${pkgs.jdk25}/bin/java" "$out/bin/jprototerm" \
                --add-flags "--enable-native-access=ALL-UNNAMED,javafx.graphics" \
                --add-flags "--module-path $out/share/jprototerm/javafx" \
                --add-flags "--add-modules javafx.controls,javafx.fxml" \
                --add-flags "-cp $classpath" \
                --add-flags "com.gregor.jprototerm.Main" \
                --prefix LD_LIBRARY_PATH : "${pkgs.lib.makeLibraryPath runtimeLibs}" \
                --run 'glShimDir="''${XDG_RUNTIME_DIR:-/tmp}/jprototerm-gl"; mkdir -p "$glShimDir"; for lib in /lib/x86_64-linux-gnu/libGL.so.1 /lib/x86_64-linux-gnu/libGLX.so.0 /lib/x86_64-linux-gnu/libGLdispatch.so.0 /usr/lib/x86_64-linux-gnu/libGLX_nvidia.so* /usr/lib/x86_64-linux-gnu/libEGL_nvidia.so* /usr/lib/x86_64-linux-gnu/libnvidia*.so* /usr/lib/x86_64-linux-gnu/nvidia/current/lib*.so*; do [ -e "$lib" ] && ln -sfn "$lib" "$glShimDir/$(basename "$lib")"; done; export LD_LIBRARY_PATH="$glShimDir''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; export __GLX_VENDOR_LIBRARY_NAME="''${__GLX_VENDOR_LIBRARY_NAME:-nvidia}"; if [ -e /usr/share/glvnd/egl_vendor.d/10_nvidia.json ]; then export __EGL_VENDOR_LIBRARY_FILENAMES="''${__EGL_VENDOR_LIBRARY_FILENAMES:-/usr/share/glvnd/egl_vendor.d/10_nvidia.json}"; fi' \
                --set JLIBGHOSTTY_LIBRARY "${ghosttyVt}/lib/libghostty-vt.so" \
                --set GDK_BACKEND x11

              runHook postInstall
            '';
          });
        in {
          default = jprototerm;
          gradleDepsUpdateScript = jprototerm.mitmCache.updateScript;
        });

      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          jlib = jlibghostty.packages.${system}.jlibghostty;
          ghosttyVt = ghostty.packages.${system}.libghostty-vt;
          runtimeLibs = runtimeLibsFor pkgs ghosttyVt;
        in {
          default = pkgs.mkShell {
            packages = [
              pkgs.gradle_9
              pkgs.jdk25
              pkgs.jdt-language-server
            ] ++ runtimeLibs;

            JLIBGHOSTTY_MAVEN_REPO = "${jlib}/maven";
            JLIBGHOSTTY_LIBRARY = "${ghosttyVt}/lib/libghostty-vt.so";
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibs;
          };
        });
    };
}
