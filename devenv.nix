{ pkgs, lib, config, inputs, ... }:

let
  system = pkgs.stdenv.hostPlatform.system;

  jlibghostty = builtins.getFlake
    "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";

  jlib = jlibghostty.packages.${system}.jlibghostty;
in
{
  packages = [
    pkgs.git
    pkgs.gradle_9
    pkgs.jdk25
    pkgs.jdt-language-server
    pkgs.openjfx

    pkgs.glib
    pkgs.xorg.libXxf86vm
    pkgs.xorg.libXrender
    pkgs.xorg.libXtst
    pkgs.xorg.libXi
    pkgs.xorg.libXrandr

    pkgs.libGL
    pkgs.gtk3
    pkgs.alsa-lib
  ];

  env.LD_LIBRARY_PATH = lib.makeLibraryPath [
    pkgs.openjfx

    pkgs.glib
    pkgs.xorg.libXxf86vm
    pkgs.xorg.libXrender
    pkgs.xorg.libXtst
    pkgs.xorg.libXi
    pkgs.xorg.libXrandr

    pkgs.libGL
    pkgs.gtk3
    pkgs.alsa-lib
  ] + ":/usr/lib/x86_64-linux-gnu/nvidia/current";
  env.JLIBGHOSTTY_MAVEN_REPO = "${jlib}/maven";
}
