{
  description = "JavaFX terminal using jlibghostty and GraalVM Native Image";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jlibghostty.url = "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";

    jtoml-all = {
      url = "https://repo.maven.apache.org/maven2/io/github/wasabithumb/jtoml-all/1.5.2/jtoml-all-1.5.2.jar";
      flake = false;
    };

    javafx-base = {
      url = "https://repo.maven.apache.org/maven2/org/openjfx/javafx-base/25/javafx-base-25-linux.jar";
      flake = false;
    };

    javafx-controls = {
      url = "https://repo.maven.apache.org/maven2/org/openjfx/javafx-controls/25/javafx-controls-25-linux.jar";
      flake = false;
    };

    javafx-graphics = {
      url = "https://repo.maven.apache.org/maven2/org/openjfx/javafx-graphics/25/javafx-graphics-25-linux.jar";
      flake = false;
    };
  };

  outputs = {
    self,
    nixpkgs,
    jlibghostty,
    jtoml-all,
    javafx-base,
    javafx-controls,
    javafx-graphics
  }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };

      jlib = jlibghostty.packages.${system}.jlibghostty;
      graalvm = pkgs.graalvmPackages.graalvm-ce;
      gradle = if pkgs ? gradle_9 then pkgs.gradle_9 else pkgs.gradle;
      openjfx = pkgs.javaPackages.openjfx25;
    in {
      packages.${system}.default = pkgs.stdenvNoCC.mkDerivation {
        pname = "jprototerm";
        version = "0.1.0";
        src = ./.;

        nativeBuildInputs = [
          graalvm
          pkgs.makeWrapper
        ];

        buildPhase = ''
          runHook preBuild

          mkdir -p build/classes build/native-image build/lib build/javafx-modules

          find src/main/java -name '*.java' | sort > build/sources.txt
          cp ${jtoml-all} build/lib/jtoml-all.jar
          cp ${javafx-base} build/javafx-modules/javafx-base.jar
          cp ${javafx-controls} build/javafx-modules/javafx-controls.jar
          cp ${javafx-graphics} build/javafx-modules/javafx-graphics.jar
          javafx_module_path="build/javafx-modules"

          jlib_classpath="$(
            find ${jlib}/maven -type f -name '*.jar' \
              ! -name '*-sources.jar' \
              ! -name '*-javadoc.jar' \
              | sort \
              | paste -sd: -
          )"
          app_classpath="build/classes:build/lib/jtoml-all.jar:$jlib_classpath:build/javafx-modules/javafx-base.jar:build/javafx-modules/javafx-controls.jar:build/javafx-modules/javafx-graphics.jar"

          javac \
            --release 25 \
            --module-path "$javafx_module_path" \
            --add-modules javafx.controls,javafx.graphics \
            -cp "build/lib/jtoml-all.jar:$jlib_classpath" \
            -d build/classes \
            @build/sources.txt

          if [ -d src/main/resources ]; then
            cp -R src/main/resources/. build/classes/
          fi

          native-image \
            --no-fallback \
            --enable-native-access=javafx.graphics \
            --module-path "$javafx_module_path" \
            --add-modules javafx.controls,javafx.graphics \
            -cp "$app_classpath" \
            -H:Class=com.gregor.jprototerm.Main \
            -o build/native-image/jprototerm

          runHook postBuild
        '';

        installPhase = ''
          runHook preInstall

          mkdir -p $out/bin
          cp build/native-image/jprototerm $out/bin/jprototerm

          wrapProgram $out/bin/jprototerm \
            --set GDK_BACKEND x11 \
            --prefix LD_LIBRARY_PATH : ${pkgs.lib.makeLibraryPath [ openjfx jlib ]}:${openjfx}/modules_libs/javafx.graphics \
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
