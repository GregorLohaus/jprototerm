{ pkgs, lib, config, inputs, ... }:

let
  system = pkgs.stdenv.hostPlatform.system;

  jlibghostty = builtins.getFlake
    "git+https://gitea.gregorlohaus.com/gregor/jlibghostty.git";

  jlib = jlibghostty.packages.${system}.jlibghostty;
  hostNvidiaLibs = ".devenv/host-nvidia-libs";
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
    pkgs.mesa-demos
  ];

  env.LD_LIBRARY_PATH = "${hostNvidiaLibs}:" + lib.makeLibraryPath [
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
  env.__GLX_VENDOR_LIBRARY_NAME = "nvidia";
  env.__EGL_VENDOR_LIBRARY_FILENAMES = "/usr/share/glvnd/egl_vendor.d/10_nvidia.json";
  env.JLIBGHOSTTY_MAVEN_REPO = "${jlib}/maven";

  enterShell = ''
    mkdir -p ${hostNvidiaLibs}
    for lib in \
      /usr/lib/x86_64-linux-gnu/libnvidia*.so* \
      /usr/lib/x86_64-linux-gnu/libGLX_nvidia.so* \
      /usr/lib/x86_64-linux-gnu/libEGL_nvidia.so* \
      /usr/lib/x86_64-linux-gnu/nvidia/current/libnvidia*.so* \
      /usr/lib/x86_64-linux-gnu/nvidia/current/libGLX_nvidia.so* \
      /usr/lib/x86_64-linux-gnu/nvidia/current/libEGL_nvidia.so*
    do
      if [ -e "$lib" ]; then
        ln -sfn "$lib" ${hostNvidiaLibs}/"$(basename "$lib")"
      fi
    done
  '';
}
