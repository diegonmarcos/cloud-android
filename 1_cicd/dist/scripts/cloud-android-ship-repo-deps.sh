# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ship-repo-deps.sh ───
#!/bin/sh
# cloud-android-ship-repo-deps — install system + android deps from config.json
set -eu
. "$(dirname "$0")/cloud-android-ship-lib.sh"

pm=$(detect_pm)
log_info "Package manager detected: $pm"

missing=""
for bin in $(deps_binaries); do
    command -v "$bin" >/dev/null 2>&1 || missing="$missing $bin"
done

if [ -z "$missing" ]; then
    log_info "All system dependencies satisfied."
else
    log_warn "Missing:$missing"
    case "$pm" in
        nix)
            log_info "Enter a nix shell with:"
            pkgs=""
            for b in $missing; do
                pkg=$(deps_pkg_name "$b" nix)
                [ -n "$pkg" ] && pkgs="$pkgs $pkg"
            done
            echo "  nix-shell -p$pkgs"
            ;;
        apt)
            log_info "Run:"
            pkgs=""
            for b in $missing; do
                pkg=$(deps_pkg_name "$b" apt)
                [ -n "$pkg" ] && pkgs="$pkgs $pkg"
            done
            echo "  sudo apt-get install -y$pkgs"
            ;;
        *)
            log_error "No supported package manager found."
            exit 1
            ;;
    esac
fi

# Android SDK components check (advisory only)
ndk=$(jq -r '.deps.android.ndk' "$CONFIG_FILE")
log_info "Required Android NDK: $ndk"
log_info "Install via: sdkmanager \"ndk;$ndk\" \"build-tools;$(jq -r '.deps.android.build_tools' "$CONFIG_FILE")\""
