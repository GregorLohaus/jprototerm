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
      javafxNativeLibraryPath = pkgs.lib.concatStringsSep ":" [
        "${openjfx}/modules_libs/javafx.base"
        "${openjfx}/modules_libs/javafx.graphics"
        "${openjfx}/modules_libs/javafx.media"
      ];
      x11 = name: oldName: pkgs.${name} or pkgs.xorg.${oldName};
      mesaDrivers = pkgs.mesa;
      runtimeLibraryPath = pkgs.lib.makeLibraryPath ([
        openjfx
        jlib
        pkgs.gtk3
        pkgs.glib
        pkgs.pango
        pkgs.cairo
        pkgs.gdk-pixbuf
        pkgs.harfbuzz
        pkgs.freetype
        pkgs.fontconfig
        pkgs.libxkbcommon
        pkgs.zlib
        pkgs.stdenv.cc.cc.lib
        pkgs.libglvnd
        (x11 "libx11" "libX11")
        (x11 "libxext" "libXext")
        (x11 "libxrender" "libXrender")
        (x11 "libxtst" "libXtst")
        (x11 "libxi" "libXi")
        (x11 "libxcursor" "libXcursor")
        (x11 "libxrandr" "libXrandr")
        (x11 "libxinerama" "libXinerama")
        (x11 "libxcb" "libxcb")
      ]
      ++ pkgs.lib.optionals (pkgs ? atk) [ pkgs.atk ]
      ++ pkgs.lib.optionals (pkgs ? libxxf86vm || pkgs.xorg ? libXxf86vm) [ (x11 "libxxf86vm" "libXxf86vm") ]
      ++ pkgs.lib.optionals (pkgs ? libGL) [ pkgs.libGL ]
      ++ pkgs.lib.optionals (pkgs ? mesa) [ pkgs.mesa ]);
      openglDriverPath = pkgs.lib.concatStringsSep ":" [
        "/run/opengl-driver/lib"
        "/run/opengl-driver-32/lib"
        "${mesaDrivers}/lib"
      ];
      driDriverPath = pkgs.lib.concatStringsSep ":" [
        "/run/opengl-driver/lib/dri"
        "/run/opengl-driver-32/lib/dri"
        "${mesaDrivers}/lib/dri"
      ];
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
            -Djava.library.path=${javafxNativeLibraryPath} \
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
            --set LIBGL_DRIVERS_PATH ${driDriverPath} \
            --set JAVA_TOOL_OPTIONS "-Dprism.order=es2,sw -Dprism.verbose=true" \
            --add-flags "-Djava.library.path=${javafxNativeLibraryPath}" \
            --add-flags "-Dprism.order=es2,sw" \
            --add-flags "-Dprism.verbose=true" \
            --prefix LD_LIBRARY_PATH : ${javafxNativeLibraryPath}:${runtimeLibraryPath}:${openglDriverPath} \
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
