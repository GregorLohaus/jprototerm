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
    in {
      packages.${system}.default = pkgs.stdenvNoCC.mkDerivation {
        pname = "jprototerm";
        version = "0.1.0";
        src = ./.;

        nativeBuildInputs = [
          graalvm
          pkgs.gradle
          pkgs.makeWrapper
        ];

        buildPhase = ''
          runHook preBuild

          export HOME=$TMPDIR/home
          export GRADLE_USER_HOME=$TMPDIR/gradle
          mkdir -p "$HOME" "$GRADLE_USER_HOME"

          gradle \
            --no-daemon \
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
          pkgs.gradle
          pkgs.util-linux
        ];

        shellHook = ''
          export JLIBGHOSTTY_MAVEN_REPO=${jlib}/maven
          echo "Use: gradle -PjlibghosttyMavenRepo=$JLIBGHOSTTY_MAVEN_REPO run"
        '';
      };
    };
}
