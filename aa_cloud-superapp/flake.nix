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
        #    mirrored in build.json::toolchain — keep them in sync. These are
        #    the args common to BOTH the lean build SDK and the heavy emulator
        #    SDK (DRY — change once).
        baseAndroidArgs = {
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
        };

        # Lean BUILD SDK — NO emulator, NO system images. Used by
        # devShells.default (i.e. `build.sh build`), so a plain APK build never
        # pulls the ~hundreds-of-MB emulator + system-image closure.
        androidEnv = pkgs.androidenv.composeAndroidPackages baseAndroidArgs;
        androidSdk = androidEnv.androidsdk;

        # Heavy EMULATOR SDK — adds the emulator + an arm64 system image, ONLY
        # for `build.sh emulator` (full-fidelity arm64 testing, no libhoudini).
        # Kept out of the default build shell. ABI/API must match
        # build.json::emulator.system_image
        # (system-images;android-34;google_apis;arm64-v8a). One composition →
        # emulator + avdmanager + image share a single ANDROID_HOME (required).
        emulatorEnv = pkgs.androidenv.composeAndroidPackages (baseAndroidArgs // {
          includeEmulator     = true;
          includeSystemImages = true;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "arm64-v8a" ];
        });
        emulatorSdk = emulatorEnv.androidsdk;
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

        # Separate, heavy shell for `build.sh emulator` only. Carries the
        # emulator + arm64 system image (emulatorSdk) so the default build
        # shell stays lean. build.sh reaches it via `nix develop .#emulator`.
        devShells.emulator = pkgs.mkShell {
          name = "superapp-emulator-devshell";
          buildInputs = with pkgs; [
            jdk17
            emulatorSdk       # emulator + avdmanager + arm64 system image
            android-tools     # adb
            jq                # build.sh reads build.json::emulator
          ];
          ANDROID_HOME = "${emulatorSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${emulatorSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
        };

        # ── APK package (placeholder — needs gradle wrapper + lockfile to be
        #    fully hermetic). For now, devShell + ./build.sh build is the path.
        packages.default = pkgs.runCommandLocal "superapp-stub" {} ''
          mkdir -p $out
          echo "TODO: hermetic APK build needs gradle wrapper checked in + dependency lockfile" > $out/README
        '';
      });
}
