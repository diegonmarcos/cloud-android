{
  description = "Diego Cloud News — Nix devShell wrapping the Android Gradle build (gradle + AGP + Android SDK + JDK 17). All toolchain pinned; same input → same APK.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
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

        baseAndroidArgs = {
          toolsVersion        = "26.1.1";
          platformToolsVersion = "35.0.2";
          buildToolsVersions  = [ "35.0.0" "34.0.0" ];
          platformVersions    = [ "36" "35" "34" "26" ];
          includeNDK          = false;
        };

        androidEnv = pkgs.androidenv.composeAndroidPackages baseAndroidArgs;
        androidSdk = androidEnv.androidsdk;

        emulatorEnv = pkgs.androidenv.composeAndroidPackages (baseAndroidArgs // {
          includeEmulator     = true;
          includeSystemImages = true;
          systemImageTypes    = [ "google_apis" ];
          abiVersions         = [ "arm64-v8a" ];
        });
        emulatorSdk = emulatorEnv.androidsdk;
      in {
        devShells.default = pkgs.mkShell {
          name = "cloud-news-devshell";
          buildInputs = with pkgs; [
            jdk17
            gradle_8
            kotlin
            androidSdk
            adb-sync
            android-tools
            jq
            oras
            gh
            sops
            age
          ];

          shellHook = ''
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools/bin:$PATH"
            echo "[cloud-news devshell] ANDROID_HOME=$ANDROID_HOME"
          '';
        };

        devShells.emulator = pkgs.mkShell {
          name = "cloud-news-emulator-devshell";
          buildInputs = with pkgs; [ jdk17 gradle_8 emulatorSdk android-tools ];
          shellHook = ''
            export ANDROID_HOME="${emulatorSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
          '';
        };
      });
}
