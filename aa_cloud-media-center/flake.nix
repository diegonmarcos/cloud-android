{
  description = "Cloud Media Center — Nix devShell wrapping the Android Gradle build for the ReFra-fork gallery APK. gradle + AGP + Android SDK + JDK 17. All toolchain pinned; same input → same APK. No Node/Cordova (pure Kotlin/Compose fork, unlike the Acode fork in ea_cloud-ide).";

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
        # keep in sync. Dictated by upstream ReFra's app/build.gradle.kts
        # (verified 2026-07-26 on main): compileSdk/targetSdk 37, ndkVersion
        # 29.0.14033849, CMake 3.31.6. ReFra is NOT pure Kotlin/Compose — it
        # has externalNativeBuild + CMake with JNI over libheif/libde265, so
        # the NDK/CMake pins below are REQUIRED, not schema-parity filler.
        androidEnv = pkgs.androidenv.composeAndroidPackages {
          toolsVersion         = "26.1.1";
          platformToolsVersion = "35.0.2";
          buildToolsVersions   = [ "37.0.0" "35.0.0" "34.0.0" ];
          platformVersions     = [ "37" "35" "34" "26" ];
          includeNDK           = true;
          # TODO(media-center): verify 29.0.14033849 / 3.31.6 are actually
          # published in the nixpkgs/nixos-24.11 androidenv package set pinned
          # by this flake's `inputs.nixpkgs`. If not available at this exact
          # version, the nixpkgs pin may need bumping — do not silently
          # substitute a different NDK/CMake version here.
          ndkVersions          = [ "29.0.14033849" ];
          cmakeVersions        = [ "3.31.6" ];
          includeEmulator      = false;
          includeSystemImages  = false;
        };

        androidSdk = androidEnv.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "cloud-media-center-devshell";
          buildInputs = with pkgs; [
            jdk17
            gradle_8
            kotlin
            androidSdk
            android-tools     # adb, fastboot
            jq                # build.sh reads build.json
            oras              # OCI artifact push to ghcr (release.ghcr)
            gh                # GitHub Release / gh run list
            git               # metadata (short sha for release tags) + fork tracker clone
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

          shellHook = ''
            echo "Cloud Media Center devShell"
            echo "  java=$(java -version 2>&1 | head -1)"
            echo "  gradle=$(gradle --version | grep '^Gradle' || true)"
            echo "  android-sdk=$ANDROID_HOME"
            echo "Commands: see README.md — build.sh engine choice is OPEN, not yet written."
          '';
        };

        # Hermetic APK build needs a checked-in gradle wrapper + dependency
        # lockfile. For now the devShell is the path, matching the other
        # ea_cloud-* Android projects. This app additionally has NO build.sh
        # yet at all (see README.md ## OPEN: engine choice) so packages.default
        # is a stub, same honest-placeholder pattern as ea_cloud-ide.
        packages.default = pkgs.runCommandLocal "cloud-media-center-stub" {} ''
          mkdir -p $out
          echo "TODO: hermetic APK build needs gradle wrapper checked in + dependency lockfile; also see README.md ## OPEN: engine choice (build.sh not written yet)" > $out/README
        '';
      });
}
