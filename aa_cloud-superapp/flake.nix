{
  description = "Diego Superapp — Nix devShell wrapping the Android Gradle build (gradle + AGP + Android SDK + JDK 17). All toolchain pinned; same input → same APK.";

  inputs = {
    # Bumped 24.11 -> 25.05: HeliBoard main (libs:keyboard) needs the
    # Android 16 (API 36) / Kotlin 2.3 / Gradle 8.14 toolchain, none of which
    # exist in 24.11's androidenv/kotlin/gradle. This re-pins the WHOLE devShell.
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
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
          # build-tools 36.0.0 required by compileSdk 36 (Android 16, driven by
          # libs:keyboard = HeliBoard main — see build.json::toolchain). 35.0.0
          # kept for the rest of the modules + transitional / NDK builds.
          buildToolsVersions  = [ "36.0.0" "35.0.0" "34.0.0" ];
          platformVersions    = [ "36" "35" "34" "26" ];
          # Two NDKs, pinned per module:
          #   • 26.1.10909125 — libs:net (wireguard-android tunnel/ → libwg-go.so
          #     via CMake). Kept on 26.1 to avoid re-validating wireguard-go.
          #   • 28.0.13004108 — libs:keyboard (HeliBoard ndk-build of
          #     libjni_latinime.so). ndkVersion in each module's build.gradle
          #     selects which; this list keeps both available in the SDK.
          includeNDK          = true;
          ndkVersions         = [ "28.0.13004108" "26.1.10909125" ];
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
            gnumake           # `build.sh firestack` runs firestack's Makefile (gomobile bind)
            curl              # `build.sh firestack` self-downloads the pinned Go (like libwg-go)
          ];

          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/36.0.0/aapt2";

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
