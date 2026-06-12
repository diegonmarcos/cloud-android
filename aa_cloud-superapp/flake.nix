{
  description = "Diego Superapp — Nix devShell wrapping the Android Gradle build (gradle + AGP + Android SDK + JDK 17). All toolchain pinned; same input → same APK.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # Android Gradle Plugin needs a specific NDK + build-tools combo. The
        # `allowUnfree` is required because Google's Android SDK is unfree.
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # ── Pin SDK/build-tools/NDK in one place. Toolchain versions are
        #    mirrored in build.json::toolchain — keep them in sync.
        androidEnv = pkgs.androidenv.composeAndroidPackages {
          toolsVersion        = "26.1.1";
          platformToolsVersion = "35.0.2";
          # build-tools 35.0.0 required by compileSdk 35 (driven by
          # androidx.health.connect:connect-client:1.1.0-alpha10 — see
          # build.json::toolchain._doc_sdk_bump). 34.0.0 kept for
          # transitional / NDK builds that still pin to it.
          buildToolsVersions  = [ "35.0.0" "34.0.0" ];
          platformVersions    = [ "35" "34" "26" ];
          # libs:net (cherry-picked wireguard-android tunnel/) builds
          # libwg-go.so via CMake → an NDK toolchain Make wrapper around
          # wireguard-go. ndkVersion in libs/net/build.gradle pins the
          # exact NDK release; this list keeps it available in the SDK.
          includeNDK          = true;
          ndkVersions         = [ "26.1.10909125" ];
          cmakeVersions       = [ "3.22.1" ];
          # Emulator + an arm64 system image for FULL-FIDELITY testing of the
          # arm64-v8a APK (`./build.sh emulator`). On this x86_64 host the
          # emulator emulates arm64 wholesale (TCG/software — slow but faithful),
          # so it needs NO libhoudini translation, unlike the Waydroid path.
          # The image ABI + API here must match build.json::emulator.system_image
          # (system-images;android-34;google_apis;arm64-v8a).
          includeEmulator     = true;
          includeSystemImages = true;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "arm64-v8a" ];
        };

        # Convenience env vars Gradle expects to find.
        androidSdk = androidEnv.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "superapp-devshell";
          buildInputs = with pkgs; [
            jdk17
            gradle_8
            kotlin
            androidSdk
            adb-sync
            android-tools     # adb, fastboot
            jq                # build.sh reads build.json
            oras              # OCI artifact push to ghcr (release.ghcr)
            gh                # GitHub Release attachment (release.gh_release)
            git               # for `git rev-parse --short` in build.sh
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

          shellHook = ''
            echo "Diego Superapp devShell"
            echo "  java=$(java -version 2>&1 | head -1)"
            echo "  gradle=$(gradle --version | grep '^Gradle' || true)"
            echo "  android-sdk=$ANDROID_HOME"
            echo "Commands: ./build.sh {build|release|dev|test|lint|clean|shell|ship}"
          '';
        };

        # ── APK package (placeholder — needs gradle wrapper + lockfile to be
        #    fully hermetic). For now, devShell + ./build.sh build is the path.
        packages.default = pkgs.runCommandLocal "superapp-stub" {} ''
          mkdir -p $out
          echo "TODO: hermetic APK build needs gradle wrapper checked in + dependency lockfile" > $out/README
        '';
      });
}
