{
  description = "jprototerm";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jlibghostty.url = "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";
  };

  outputs = { self, nixpkgs, jlibghostty }:
    let
      supportedSystems = [ "x86_64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    in {
      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };

          jlib = jlibghostty.packages.${system}.jlibghostty;

          javafxStaticSdkZip = pkgs.fetchurl {
            url = "https://download2.gluonhq.com/substrate/javafxstaticsdk/openjfx-21-ea+11.3-linux-x86_64-static.zip";
            hash = "sha256-ovoByMPwhvU54mtxGYyrgLxDLNn0tA3XUK3rtnGfAAM=";
          };

          patchedSubstrateJar =
            let
              substrateJar = pkgs.fetchurl {
                url = "https://plugins.gradle.org/m2/com/gluonhq/substrate/0.0.68/substrate-0.0.68.jar";
                hash = "sha256-ZOchzSN8IPSQVnItGAvo3T6OG93G0lqU+BmemdFa6lE=";
              };
            in
            pkgs.runCommand "substrate-0.0.68-patched.jar"
              {
                nativeBuildInputs = [
                  pkgs.perl
                  pkgs.unzip
                  pkgs.zip
                ];
              }
              ''
                mkdir work
                cd work
                unzip -q ${substrateJar}

                # Gluon Substrate hardcodes /usr/bin/pkg-config. Keep the
                # replacement string the same byte length to avoid rewriting
                # the Java class constant pool structure.
                perl -0pi -e 's|/usr/bin/pkg-config|/tmp/nix/pkg-config|g' \
                  com/gluonhq/substrate/util/linux/LinuxLinkerFlags.class

                if grep -a -q "/usr/bin/pkg-config" com/gluonhq/substrate/util/linux/LinuxLinkerFlags.class; then
                  echo "Failed to patch hardcoded pkg-config path in Substrate" >&2
                  exit 1
                fi

                zip -qr "$out" .
              '';

          gluonGraalvm = pkgs.stdenv.mkDerivation {
            pname = "graalvm-java23-gluon";
            version = "23+25.1-dev-2409082136";

            src = pkgs.fetchurl {
              url = "https://github.com/gluonhq/graal/releases/download/gluon-23%2B25.1-dev-2409082136/graalvm-java23-linux-amd64-gluon-23+25.1-dev.tar.gz";
              hash = "sha256-/NyMutn3pT4ZKL2pkzPdBZghxg0ERK5VJ2bFQF0VBfU=";
            };

            nativeBuildInputs = [
              pkgs.autoPatchelfHook
            ];

            buildInputs = [
              pkgs.stdenv.cc.cc.lib
              pkgs.zlib
              pkgs.freetype
              pkgs.fontconfig
              pkgs.alsa-lib
              pkgs.glib
              pkgs.gtk3
              pkgs.pango
              pkgs.libx11
              pkgs.libxext
              pkgs.libxrender
              pkgs.libxtst
              pkgs.libxi
              pkgs.libxrandr
              pkgs.libxinerama
              pkgs.libxcb
            ];

            installPhase = ''
              runHook preInstall

              mkdir -p "$out"
              cp -R . "$out/"

              # The GluonFX Gradle plugin expects these two static libraries
              # directly under linux-amd64, but this tarball keeps them one
              # level deeper under linux-amd64/glibc.
              ln -sfn ./glibc/libjvm.a "$out/lib/svm/clibraries/linux-amd64/libjvm.a"
              ln -sfn ./glibc/liblibchelper.a "$out/lib/svm/clibraries/linux-amd64/liblibchelper.a"

              runHook postInstall
            '';
          };

          runtimeLibs = [
            pkgs.glib
            pkgs.gtk3
            pkgs.pango
            pkgs.alsa-lib
            pkgs.ffmpeg.dev
            pkgs.ffmpeg.lib
            pkgs.freetype
            pkgs.fontconfig
            pkgs.libx11
            pkgs.libx11.dev
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
            pkgs.zlib.dev
          ];
          jprototerm = pkgs.stdenv.mkDerivation (finalAttrs: {
            pname = "jprototerm";
            version = "0.1.0";
            src = ./.;

            nativeBuildInputs = [
              gluonGraalvm
              pkgs.gradle_9
              pkgs.makeWrapper
              pkgs.patchelf
              pkgs.pkg-config
            ];

            buildInputs = runtimeLibs;

            baseMitmCache = pkgs.gradle_9.fetchDeps {
              pkg = finalAttrs.finalPackage;
              data = ./deps.json;
              silent = false;
              useBwrap = false;
            };

            mitmCache = pkgs.runCommand "jprototerm-deps-patched"
              {
                passthru.updateScript = finalAttrs.baseMitmCache.updateScript;
              }
              ''
                mkdir -p "$out"
                cp -a ${finalAttrs.baseMitmCache}/. "$out/"
                chmod -R u+w "$out"

                substratePath="$out/https/plugins.gradle.org/m2/com/gluonhq/substrate/0.0.68/substrate-0.0.68.jar"
                if [ ! -e "$substratePath" ]; then
                  echo "Could not find substrate jar in Gradle MITM cache: $substratePath" >&2
                  find "$out" -path '*substrate*' -print >&2
                  exit 1
                fi

                rm "$substratePath"
                ln -s ${patchedSubstrateJar} "$substratePath"
              '';

            gradleBuildTask = "nativeBuild";
            gradleUpdateTask = "nixDownloadDeps";
            gradleFlags = [
              "--no-build-cache"
              "--stacktrace"
              "--info"
              "-Dorg.gradle.java.home=${gluonGraalvm}"
            ];

            GRAALVM_HOME = "${gluonGraalvm}";
            JAVA_HOME = "${gluonGraalvm}";
            JLIBGHOSTTY_MAVEN_REPO = "${jlib}/maven";

            preConfigure = ''
              export HOME="$TMPDIR/home"
              export GRADLE_OPTS="-Duser.home=$HOME ''${GRADLE_OPTS:-}"
              mkdir -p /tmp/nix
              ln -sfn ${pkgs.pkg-config}/bin/pkg-config /tmp/nix/pkg-config
              for gluonHome in "$HOME/.gluon" /build/.gluon; do
                mkdir -p "$gluonHome/substrate"
                cp -f ${javafxStaticSdkZip} "$gluonHome/substrate/openjfx-21-ea+11.3-linux-x86_64-static.zip"
                chmod u+w "$gluonHome/substrate/openjfx-21-ea+11.3-linux-x86_64-static.zip"
              done
            '';

            preBuild = ''
              export HOME="$TMPDIR/home"
              export GRADLE_OPTS="-Duser.home=$HOME ''${GRADLE_OPTS:-}"
              mkdir -p /tmp/nix
              ln -sfn ${pkgs.pkg-config}/bin/pkg-config /tmp/nix/pkg-config
              for gluonHome in "$HOME/.gluon" /build/.gluon; do
                mkdir -p "$gluonHome/substrate"
                cp -f ${javafxStaticSdkZip} "$gluonHome/substrate/openjfx-21-ea+11.3-linux-x86_64-static.zip"
                chmod u+w "$gluonHome/substrate/openjfx-21-ea+11.3-linux-x86_64-static.zip"
              done
              export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath runtimeLibs}:$LD_LIBRARY_PATH"
            '';

            preGradleUpdate = ''
              export HOME="$TMPDIR/home"
              export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath runtimeLibs}:$LD_LIBRARY_PATH"
            '';

            installPhase = ''
              runHook preInstall

              mkdir -p "$out/bin"
              binary="$(find build/gluonfx -path '*/gvm/*' -prune -o -type f -perm -0100 -print | head -n1)"
              if [ -z "$binary" ]; then
                echo "Could not find native executable under build/gluonfx" >&2
                find build/gluonfx -type f -perm -0100 >&2 || true
                exit 1
              fi

              cp "$binary" "$out/bin/jprototerm"

              wrapProgram "$out/bin/jprototerm" \
                --run 'glShimDir="''${XDG_RUNTIME_DIR:-/tmp}/jprototerm-gl"; mkdir -p "$glShimDir"; for lib in /lib/x86_64-linux-gnu/libGL.so.1 /lib/x86_64-linux-gnu/libGLX.so.0 /lib/x86_64-linux-gnu/libGLdispatch.so.0 /usr/lib/x86_64-linux-gnu/libGLX_nvidia.so.0 /usr/lib/x86_64-linux-gnu/nvidia/current/lib*.so*; do [ -e "$lib" ] && ln -sfn "$lib" "$glShimDir/$(basename "$lib")"; done; export LD_LIBRARY_PATH="$glShimDir''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; export __GLX_VENDOR_LIBRARY_NAME="''${__GLX_VENDOR_LIBRARY_NAME:-nvidia}"' \
                --prefix LD_LIBRARY_PATH : "${pkgs.lib.makeLibraryPath runtimeLibs}" \
                --set GDK_BACKEND x11

              runHook postInstall
            '';

            postFixup = ''
              binary="$out/bin/.jprototerm-wrapped"
              currentRpath="$(patchelf --print-rpath "$binary" || true)"
              filteredRpath="$(printf '%s' "$currentRpath" | tr ':' '\n' | grep -v 'libglvnd' | paste -sd: -)"
              patchelf --set-rpath "$filteredRpath" "$binary"
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
          runtimeLibs = [
            pkgs.glib
            pkgs.gtk3
            pkgs.pango
            pkgs.alsa-lib
            pkgs.ffmpeg.dev
            pkgs.ffmpeg.lib
            pkgs.freetype
            pkgs.fontconfig
            pkgs.libx11
            pkgs.libx11.dev
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
            pkgs.zlib.dev
          ];
        in {
          default = pkgs.mkShell {
            packages = [
              pkgs.gradle_9
              pkgs.jdk23
              pkgs.jdt-language-server
            ] ++ runtimeLibs;

            JLIBGHOSTTY_MAVEN_REPO = "${jlib}/maven";
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibs;
          };
        });
    };
}
