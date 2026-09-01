#!/usr/bin/env python3
"""Rewrites the package-id path baked into a termux-packages bootstrap zip.

The upstream bootstrap zip ships prebuilt ELF binaries (and text config
files) with the literal absolute path "/data/data/com.termux/files/..."
compiled/written in as a fixed-length string. This fork renames the
applicationId to a SAME-LENGTH id (cld.termux / cld.termux.nix) specifically
so this byte-for-byte substitution keeps every offset inside those binaries
valid -- no relinking, no rpath surgery, just find+replace.

Usage:
    patch_bootstrap_ids.py <input.zip> <output.zip> <old=new> [<old=new> ...]

Replacement pairs are applied in the order given, per entry, over the raw
bytes of every zip entry. Callers must order longer/more-specific ids first
(e.g. com.termux.nix before com.termux) so a bare com.termux inside a
com.termux.nix path isn't replaced first and left inconsistent.

Hard-fails (non-zero exit) if, after patching:
  - any entry still contains one of the "old" id byte strings, or
  - the output zip does not have exactly the same number of entries as
    the input zip (nothing silently dropped during rezip).
"""
import sys
import zipfile


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: patch_bootstrap_ids.py <input.zip> <output.zip> <old=new> [<old=new> ...]", file=sys.stderr)
        return 2

    input_zip, output_zip = sys.argv[1], sys.argv[2]
    pairs = []
    for raw in sys.argv[3:]:
        old, _, new = raw.partition("=")
        if not old or not new:
            print(f"bad replacement pair (want old=new): {raw!r}", file=sys.stderr)
            return 2
        if len(old) != len(new):
            print(f"refusing unequal-length replacement {old!r} -> {new!r} "
                  f"({len(old)} != {len(new)} bytes): would shift every "
                  f"offset baked into the bootstrap ELF binaries", file=sys.stderr)
            return 2
        pairs.append((old.encode(), new.encode()))

    with zipfile.ZipFile(input_zip, "r") as zin:
        in_infos = zin.infolist()
        with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zout:
            patched_entries = 0
            for info in in_infos:
                data = zin.read(info.filename)
                new_data = data
                for old, new in pairs:
                    new_data = new_data.replace(old, new)
                if new_data != data:
                    patched_entries += 1
                # Preserve compression type, unix perms (external_attr) and
                # timestamps by writing back the same ZipInfo object.
                zout.writestr(info, new_data)
            print(f"patched {patched_entries}/{len(in_infos)} entries in {input_zip}")

    # --- verification gate ---
    old_ids = [old for old, _ in pairs]
    with zipfile.ZipFile(output_zip, "r") as zout:
        out_infos = zout.infolist()
        if len(out_infos) != len(in_infos):
            print(f"FAIL: entry count changed during rezip: "
                  f"{len(in_infos)} -> {len(out_infos)} ({output_zip})", file=sys.stderr)
            return 1
        hits = []
        for info in out_infos:
            data = zout.read(info.filename)
            for old in old_ids:
                if old in data:
                    hits.append((info.filename, old.decode()))
        if hits:
            print(f"FAIL: {len(hits)} old-id occurrence(s) survived patching "
                  f"in {output_zip}:", file=sys.stderr)
            for filename, old in hits[:20]:
                print(f"  {filename}: contains {old!r}", file=sys.stderr)
            return 1

    print(f"OK: {output_zip} verified clean of {[o.decode() for o in old_ids]}, "
          f"{len(out_infos)} entries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
