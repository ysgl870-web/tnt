#!/usr/bin/env python3
"""Compare DEX class definitions in two Android module APKs (Python stdlib only)."""
import argparse
import hashlib
import json
import re
import struct
import tempfile
import zipfile
from pathlib import Path


def uleb(data, offset):
    value = shift = 0
    for _ in range(5):
        if offset >= len(data):
            raise ValueError("truncated ULEB128")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7f) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
    raise ValueError("invalid ULEB128")


def dex_classes(data):
    if len(data) < 112 or data[:4] != b"dex\n":
        raise ValueError("not a DEX file")
    file_size, header_size = struct.unpack_from("<II", data, 32)
    if file_size > len(data) or header_size < 112:
        raise ValueError("invalid DEX header")
    nstr, ostr = struct.unpack_from("<II", data, 56)
    ntype, otype = struct.unpack_from("<II", data, 64)
    nclass, oclass = struct.unpack_from("<II", data, 96)
    for offset, size in ((ostr, nstr * 4), (otype, ntype * 4), (oclass, nclass * 32)):
        if offset < 0 or offset + size > len(data):
            raise ValueError("DEX section outside file")
    strings = []
    for i in range(nstr):
        pos = struct.unpack_from("<I", data, ostr + i * 4)[0]
        _, pos = uleb(data, pos)
        end = data.find(b"\0", pos)
        if end < 0:
            raise ValueError("unterminated DEX string")
        strings.append(data[pos:end].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, otype + i * 4)[0]] for i in range(ntype)]
    classes = set()
    for i in range(nclass):
        index = struct.unpack_from("<I", data, oclass + i * 32)[0]
        desc = types[index]
        classes.add(desc[1:-1].replace("/", ".") if desc.startswith("L") else desc)
    return classes


def inspect_apk(path):
    temp = None
    apk = path
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as outer:
            candidates = [name for name in outer.namelist() if name.lower().endswith(".apk")]
            if len(candidates) != 1:
                raise ValueError(f"{path}: expected one embedded APK, found {len(candidates)}")
            temp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
            temp.write(outer.read(candidates[0]))
            temp.close()
            apk = Path(temp.name)
    try:
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\\.dex", Path(n).name)]
            names.sort(key=lambda n: 1 if Path(n).name == "classes.dex" else int(re.search(r"(\\d+)", n).group(1)))
            classes, dex_stats = set(), []
            for name in names:
                raw = archive.read(name)
                defined = dex_classes(raw)
                classes.update(defined)
                dex_stats.append({"file": name, "bytes": len(raw), "class_definitions": len(defined)})
            metadata = {}
            for name in ("META-INF/xposed/module.prop", "META-INF/xposed/scope.list", "META-INF/xposed/java_init.list"):
                try:
                    metadata[name] = archive.read(name).decode("utf-8", "replace").strip()
                except KeyError:
                    metadata[name] = None
            libs = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in archive.namelist()
                    if n.startswith("lib/") and n.endswith(".so")}
        return {"input": str(path), "apk_bytes": apk.stat().st_size,
                "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "dex": dex_stats, "class_definition_count": len(classes),
                "module_metadata": metadata, "native_library_sha256": libs, "classes": classes}
    finally:
        if temp:
            Path(temp.name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("awa", type=Path, help="AWA APK or ZIP containing one APK")
    parser.add_argument("hicores", type=Path, help="Hicores APK")
    parser.add_argument("--list-unique", action="store_true")
    args = parser.parse_args()
    awa, hicores = inspect_apk(args.awa), inspect_apk(args.hicores)
    a, h = awa.pop("classes"), hicores.pop("classes")
    result = {"warning": "Static DEX metadata does not prove runtime behavior, source equivalence or APK safety.",
              "awa": awa, "hicores": hicores,
              "comparison": {"shared_class_definitions": len(a & h),
                             "awa_only_class_definitions": len(a - h),
                             "hicores_only_class_definitions": len(h - a)}}
    if args.list_unique:
        result["comparison"]["awa_only_classes"] = sorted(a - h)
        result["comparison"]["hicores_only_classes"] = sorted(h - a)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())