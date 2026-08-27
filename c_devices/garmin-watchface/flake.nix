{
  description = "Garmin Connect IQ watch faces — declarative SDK toolchain + build devShell (the Nix way, no Docker)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";

    # connect-iq-sdk-manager-cli — reproducibly downloads + pins the proprietary
    # Connect IQ SDK (which is NOT in nixpkgs). Pinned by rev here; the SDK
    # version itself is pinned by build.json::toolchain.ciq_sdk + the committed
    # connect-iq-sdk-manager lockfile. This is how we stay declarative without
    # vendoring Garmin's binary blob into the repo.
    ciq-sdk-manager = {
      url = "github:lindell/connect-iq-sdk-manager-cli";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, flake-utils, ciq-sdk-manager }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        # The SDK manager CLI (Go). vendorHash is a fixed-output hash of the Go
        # module deps: on first `nix build`/`nix develop`, Nix prints the real
        # hash — replace lib.fakeHash with it once, then commit. Standard FOD
        # bootstrap, not a workaround.
        sdkManager = pkgs.buildGoModule {
          pname = "connect-iq-sdk-manager";
          version = "0-unstable";
          src = ciq-sdk-manager;
          vendorHash = pkgs.lib.fakeHash; # TODO(one-time): replace with hash Nix prints
          meta.description = "CLI to download/pin the Garmin Connect IQ SDK";
        };

        # Tools every build.sh path needs. monkeyc/connectiq/monkeydo come from
        # the SDK materialised by sdkManager into ./.ciq-sdk (see shellHook),
        # pinned by the committed lockfile so it's reproducible across machines.
        tools = with pkgs; [
          jdk17          # monkeyc is a JVM tool (bin/monkeybrains.jar)
          sdkManager
          python3        # lib/gen-design.py (stdlib only)
          jq             # build.json / design.json reads
          openssl        # developer-key generation/conversion
          oras           # GHCR OCI-artifact publish (mirrors aa_cloud-superapp)
          gh             # GitHub rolling-release publish
          sops age       # vault signing-key decrypt (Pillar 7)
          coreutils gnused gawk
        ];
      in {
        devShells.default = pkgs.mkShell {
          packages = tools;

          # The flake provides the TOOLS (CLI + jdk + helpers); `build.sh sdk`
          # drives the actual SDK provisioning (agreement accept → login → sdk
          # set → device download) so the flow stays data-driven from build.json
          # and the Garmin login isn't buried in an eval-time hook. If the SDK is
          # already provisioned, surface its bin/ on PATH for convenience.
          shellHook = ''
            if command -v connect-iq-sdk-manager >/dev/null 2>&1; then
              _ciqbin="$(connect-iq-sdk-manager sdk current-path --bin 2>/dev/null || true)"
              [ -n "$_ciqbin" ] && [ -d "$_ciqbin" ] && export PATH="$_ciqbin:$PATH"
            fi
            echo "[flake] devShell ready. Run \`./build.sh sdk\` once to provision the Connect IQ SDK (Garmin login)."
          '';
        };

        # Pure SDK package (optional, for fully-hermetic CI). Filled once like
        # vendorHash above. Kept separate so a plain `nix develop` never forces
        # the full SDK realisation when BYPASS_NIX=1.
        packages.sdk-manager = sdkManager;
      });
}
