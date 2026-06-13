{
  description = "Cloud-IDE — Nix devShell wrapping the Android Gradle build for the hub APK + the materialized forks (gradle + AGP + Android SDK + JDK 17 + Node/Cordova for the Acode editor fork). All toolchain pinned; same input → same APK.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
    flake-utils.url = "github:numtide/flake-utils";
    # Rust with Android cross std — Amaze's :file_operations is a Rust cdylib
    # (mozilla rust-android-gradle); host rustc has no aarch64-linux-android std.
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, rust-overlay, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ rust-overlay.overlays.default ];
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Pinned stable Rust + every Android ABI the constellation can ship
        # (arm64-v8a default + x86_64 variant — the files-fork's cargo targets
        # are selected per-build via -PcloudIdeRustTargets, data-driven from
        # build.json::release.abis).
        rustAndroid = pkgs.rust-bin.stable.latest.default.override {
          targets = [ "aarch64-linux-android" "x86_64-linux-android" ];
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
          # The hub is pure-JVM. NDK kept available for the forks that bundle
          # native libs. NOTE: nixpkgs 24.11 androidenv indexes up to NDK 26 —
          # Amaze upstream pins r28c, so the files-fork patch series re-pins its
          # libs.versions.toml ndk to this version (fork builds with OUR
          # toolchain). Revisit on the next nixpkgs bump.
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
            rustAndroid       # rustc+cargo with aarch64-linux-android std (files fork)
            python3           # rust-android-gradle plugin's pythonCommand
            # Node toolchain for the Acode (Cordova) editor fork: the Cordova
            # CLI + JS plugin build. The hub + native forks don't need it, but
            # `./build.sh build-fork editor` does.
            nodejs_20
            yarn
            jq                # build.sh reads build.json + validates contract
            check-jsonschema  # `./build.sh verify-contract` schema validation
            oras              # OCI artifact push to ghcr (release.ghcr)
            gh                # GitHub Release / gh run list
            git               # materialize-fork: clone + checkout pin + apply
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
            echo "Commands: ./build.sh {build|verify-contract|materialize-fork <k>|build-fork <k>|clean|shell|ship}"
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
