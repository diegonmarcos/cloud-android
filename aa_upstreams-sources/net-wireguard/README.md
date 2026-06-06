# net-wireguard — wireguard-android

| Field | Value |
|---|---|
| Upstream | https://github.com/WireGuard/wireguard-android.git |
| License | Apache-2.0 |
| Local clone path | `../../ea_net-wireguard/` |
| Last pinned commit | `e7b3a3c` (2026-03-17) |
| Cherry-pick target | `ea_cloud-superapp/libs/net/` |
| Status | **Active** — 20 Java files cherry-picked from `tunnel/` |

## Re-clone

```bash
git clone https://github.com/WireGuard/wireguard-android.git \
  ~/git/unix/ea_net-wireguard
git -C ~/git/unix/ea_net-wireguard checkout e7b3a3c
```

## What we cherry-picked

The `tunnel/` library module — `com.wireguard.config.{Config,
Interface, Peer, InetEndpoint, InetNetwork, Attribute,
BadConfigException, …}` for config parsing + builders, and
`com.wireguard.crypto.{KeyPair, Curve25519}` for key generation.
The Go backend lib (`libwg-go.so`) is built from
`libs/net/src/main/cpp/CMakeLists.txt` at gradle assembly time.

Updates: re-run `~/git/unix/ea_cloud-superapp/build.sh sync-net`
after pulling a new upstream commit — the engine handles the
cherry-pick deterministically.
