{
  description = "JavaFX terminal using jlibghostty and GraalVM Native Image";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    jlibghostty.url = "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";

    jtoml-all = {
      url = "https://repo.maven.apache.org/maven2/io/github/wasabithumb/jtoml-all/1.5.2/jtoml-all-1.5.2.jar";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, jlibghostty, jtoml-all }:
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

          mkdir -p build/classes build/native-image

          find src/main/java -name '*.java' | sort > build/sources.txt
          javafx_module_path="${openjfx}/jmods"
          if [ ! -f "$javafx_module_path/javafx.graphics.jmod" ]; then
            echo "Could not find javafx.graphics.jmod under $javafx_module_path" >&2
            find ${openjfx} -maxdepth 4 -type f | sort >&2
            exit 1
          fi

          jlib_classpath="$(
            find ${jlib}/maven -type f -name '*.jar' \
              ! -name '*-sources.jar' \
              ! -name '*-javadoc.jar' \
              | sort \
              | paste -sd: -
          )"
          app_classpath="build/classes:${jtoml-all}:$jlib_classpath"

          javac \
            --release 25 \
            --module-path "$javafx_module_path" \
            --add-modules javafx.controls,javafx.graphics \
            -cp "${jtoml-all}:$jlib_classpath" \
            -d build/classes \
            @build/sources.txt

          if [ -d src/main/resources ]; then
            cp -R src/main/resources/. build/classes/
          fi

          native-image \
            --no-fallback \
            --enable-url-protocols=file \
            --module-path "$javafx_module_path" \
            --add-modules javafx.controls,javafx.graphics \
            -cp "$app_classpath" \
            -H:Name=jprototerm \
            -H:Class=com.gregor.jprototerm.Main \
            -H:Path=build/native-image

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
