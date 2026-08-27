{
  description = "Diego Cloud Wallet — Nix devShell wrapping the Android Gradle build (gradle + AGP + Android SDK + JDK 17). All toolchain pinned; same input → same APK.";

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

        # ── Pin SDK/build-tools in one place. Mirrored in
        #    build.json::toolchain. NO NDK / cmake: Cloud Wallet has ZERO in-tree
        #    native build — libs:maps pulls MapLibre Native + play-services as
        #    AARs that already ship their prebuilt .so, and `ndk { abiFilters }`
        #    only selects which of those .so to pack (it never invokes the NDK).
        #    Dropping the ~3 GB NDK derivation keeps the devShell lean and the
        #    build reproducible on a near-full nix store.
        baseAndroidArgs = {
          toolsVersion        = "26.1.1";
          platformToolsVersion = "35.0.2";
          buildToolsVersions  = [ "35.0.0" "34.0.0" ];
          platformVersions    = [ "35" "34" "26" ];
          includeNDK          = false;
        };

        # Lean BUILD SDK — NO emulator, NO system images.
        androidEnv = pkgs.androidenv.composeAndroidPackages baseAndroidArgs;
        androidSdk = androidEnv.androidsdk;

        # Heavy EMULATOR SDK — only for `build.sh emulator` (full-fidelity
        # arm64 testing, no libhoudini). ABI/API match build.json::emulator.
        emulatorEnv = pkgs.androidenv.composeAndroidPackages (baseAndroidArgs // {
          includeEmulator     = true;
          includeSystemImages = true;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "arm64-v8a" ];
        });
        emulatorSdk = emulatorEnv.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "cloud-wallet-devshell";
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
            echo "Diego Cloud Wallet devShell"
            echo "  java=$(java -version 2>&1 | head -1)"
            echo "  gradle=$(gradle --version | grep '^Gradle' || true)"
            echo "  android-sdk=$ANDROID_HOME"
            echo "Commands: ./build.sh {build|release|dev|test|lint|clean|shell|ship|emulator}"
          '';
        };

        # Separate, heavy shell for `build.sh emulator` only.
        devShells.emulator = pkgs.mkShell {
          name = "cloud-wallet-emulator-devshell";
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

        packages.default = pkgs.runCommandLocal "cloud-wallet-stub" {} ''
          mkdir -p $out
          echo "TODO: hermetic APK build needs gradle wrapper checked in + dependency lockfile" > $out/README
        '';
      });
}
