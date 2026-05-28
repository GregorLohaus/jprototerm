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
            pkgs.libglvnd
            pkgs.glib
            pkgs.gtk3
            pkgs.pango
            pkgs.alsa-lib
            pkgs.ffmpeg.dev
            pkgs.ffmpeg.lib
            pkgs.freetype
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
              pkgs.pkg-config
            ];

            buildInputs = runtimeLibs;

            mitmCache = pkgs.gradle_9.fetchDeps {
              pkg = finalAttrs.finalPackage;
              data = ./deps.json;
              silent = false;
              useBwrap = false;
            };

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
              for gluonHome in "$HOME/.gluon" /build/.gluon; do
                mkdir -p "$gluonHome/substrate"
                cp -f ${javafxStaticSdkZip} "$gluonHome/substrate/openjfx-21-ea+11.3-linux-x86_64-static.zip"
                chmod u+w "$gluonHome/substrate/openjfx-21-ea+11.3-linux-x86_64-static.zip"
              done
            '';

            preBuild = ''
              export HOME="$TMPDIR/home"
              export GRADLE_OPTS="-Duser.home=$HOME ''${GRADLE_OPTS:-}"
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
              binary="$(find build/gluonfx -type f -perm -0100 -name jprototerm | head -n1)"
              if [ -z "$binary" ]; then
                echo "Could not find native jprototerm binary under build/gluonfx" >&2
                find build/gluonfx -type f -perm -0100 >&2 || true
                exit 1
              fi

              cp "$binary" "$out/bin/jprototerm"
              wrapProgram "$out/bin/jprototerm" \
                --prefix LD_LIBRARY_PATH : "${pkgs.lib.makeLibraryPath runtimeLibs}" \
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
          runtimeLibs = [
            pkgs.libglvnd
            pkgs.glib
            pkgs.gtk3
            pkgs.pango
            pkgs.alsa-lib
            pkgs.ffmpeg.dev
            pkgs.ffmpeg.lib
            pkgs.freetype
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
