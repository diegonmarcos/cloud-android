{
  description = "Cloud-IDE — Nix devShell wrapping the Android Gradle build for the hub APK (single WebView app; the hub bundles the sibling da_my-konsole/frontend at build time). gradle + AGP + Android SDK + JDK 17 + Node (frontend xterm vendoring). All toolchain pinned; same input → same APK.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Toolchain pinned in one place. Mirrored in build.json::toolchain —
        # keep in sync. Same SDK/build-tools/NDK combo as ea_cloud-superapp +
        # ea_cloud-comms so all the Android projects share one reproducible
        # toolchain (and one signing/cross-talk story).
        androidEnv = pkgs.androidenv.composeAndroidPackages {
          toolsVersion         = "26.1.1";
          platformToolsVersion = "35.0.2";
          buildToolsVersions   = [ "35.0.0" "34.0.0" ];
          platformVersions     = [ "35" "34" "26" ];
          # The hub is pure-JVM; NDK not required but kept for schema parity
          # with ea_cloud-superapp / ea_cloud-comms toolchain definitions.
          includeNDK           = true;
          ndkVersions          = [ "26.1.10909125" ];
          cmakeVersions        = [ "3.22.1" ];
          includeEmulator      = false;
          includeSystemImages  = false;
        };

        androidSdk = androidEnv.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "cloud-ide-devshell";
          buildInputs = with pkgs; [
            jdk17
            gradle_8
            kotlin
            androidSdk
            android-tools     # adb, fastboot
            nodejs_20         # da_my-konsole/build.sh vendor (npm install → frontend/vendor)
            jq                # build.sh reads build.json
            oras              # OCI artifact push to ghcr (release.ghcr)
            gh                # GitHub Release / gh run list
            git               # metadata (short sha for release tags)
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

          shellHook = ''
            echo "Cloud-IDE devShell"
            echo "  java=$(java -version 2>&1 | head -1)"
            echo "  gradle=$(gradle --version | grep '^Gradle' || true)"
            echo "  node=$(node --version 2>/dev/null || echo 'n/a')"
            echo "  android-sdk=$ANDROID_HOME"
            echo "Commands: ./build.sh {build|release|bundle-frontend|clean|shell|ship}"
          '';
        };

        # Hermetic APK build needs a checked-in gradle wrapper + dependency
        # lockfile. For now the devShell + ./build.sh build is the path,
        # matching ea_cloud-superapp / ea_cloud-comms.
        packages.default = pkgs.runCommandLocal "cloud-ide-stub" {} ''
          mkdir -p $out
          echo "TODO: hermetic APK build needs gradle wrapper checked in + dependency lockfile" > $out/README
        '';
      });
}
