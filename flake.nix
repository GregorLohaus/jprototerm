{
  description = "JavaFX terminal using jlibghostty and GraalVM Native Image";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jlibghostty.url = "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";
  };

  outputs = { self, nixpkgs, jlibghostty }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };

      jlib = jlibghostty.packages.${system}.jlibghostty;
      graalvm = pkgs.graalvmPackages.graalvm-ce;
      gradle = if pkgs ? gradle_9 then pkgs.gradle_9 else pkgs.gradle;
      gradleDeps = pkgs.stdenvNoCC.mkDerivation {
        pname = "jprototerm-gradle-deps";
        version = "0.1.0";
        src = ./.;

        nativeBuildInputs = [
          graalvm
          gradle
        ];

        outputHashAlgo = "sha256";
        outputHashMode = "recursive";
        outputHash = pkgs.lib.fakeHash;

        buildPhase = ''
          runHook preBuild

          export HOME=$TMPDIR/home
          export GRADLE_USER_HOME=$out
          mkdir -p "$HOME" "$GRADLE_USER_HOME"

          gradle \
            --no-daemon \
            --stacktrace \
            -PjlibghosttyMavenRepo=${jlib}/maven \
            help

          gradle \
            --no-daemon \
            --stacktrace \
            -PjlibghosttyMavenRepo=${jlib}/maven \
            dependencies \
            --configuration runtimeClasspath

          runHook postBuild
        '';

        installPhase = ''
          runHook preInstall
          runHook postInstall
        '';
      };
    in {
      packages.${system}.default = pkgs.stdenvNoCC.mkDerivation {
        pname = "jprototerm";
        version = "0.1.0";
        src = ./.;

        nativeBuildInputs = [
          graalvm
          gradle
          pkgs.makeWrapper
        ];

        buildPhase = ''
          runHook preBuild

          export HOME=$TMPDIR/home
          export GRADLE_USER_HOME=$TMPDIR/gradle
          mkdir -p "$HOME" "$GRADLE_USER_HOME"
          cp -R ${gradleDeps}/. "$GRADLE_USER_HOME"
          chmod -R u+w "$GRADLE_USER_HOME"

          gradle \
            --no-daemon \
            --offline \
            --stacktrace \
            -PjlibghosttyMavenRepo=${jlib}/maven \
            nativeCompile

          runHook postBuild
        '';

        installPhase = ''
          runHook preInstall

          mkdir -p $out/bin
          cp build/gluonfx/*/*/jprototerm $out/bin/jprototerm

          wrapProgram $out/bin/jprototerm \
            --set GDK_BACKEND x11 \
            --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.util-linux pkgs.bash ]}

          runHook postInstall
        '';
      };

      devShells.${system}.default = pkgs.mkShell {
        packages = [
          graalvm
          gradle
          pkgs.util-linux
        ];

        shellHook = ''
          export JLIBGHOSTTY_MAVEN_REPO=${jlib}/maven
          echo "Use: gradle -PjlibghosttyMavenRepo=$JLIBGHOSTTY_MAVEN_REPO run"
        '';
      };
    };
}
