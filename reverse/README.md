# AWA reverse-engineering workspace

This directory keeps local APK inputs and generated reverse-engineering output out of Git by default.

## Run

From the repository root:

```bash
mkdir -p reverse/input
# Copy awa-main.zip or awa.apk into reverse/input/ with your file manager.
bash tools/reverse_awa_apk.sh reverse/input/awa-main.zip
```

The ZIP must contain exactly one APK. Output is written to reverse/output/awa-<sha256-prefix>/.

## Tools

- JDK 17+
- JADX CLI 1.5.6 or compatible: https://github.com/skylot/jadx/releases
- Apktool 3.0.3 or compatible: https://github.com/iBotPeaches/Apktool/releases
- Python 3

For Apktool as a JAR:

```bash
APKTOOL_JAR=/path/to/apktool_3.0.3.jar bash tools/reverse_awa_apk.sh reverse/input/awa-main.zip
```

For JADX outside PATH:

```bash
JADX_BIN=/path/to/jadx/bin/jadx bash tools/reverse_awa_apk.sh reverse/input/awa-main.zip
```

## Output

- jadx/: Java-like output generated from DEX.
- apktool/: Smali, decoded Manifest and Android resources.
- REPORT.md: input hash, tool versions and limitations.
- SHA256SUMS.txt: generated file hashes.

The uploaded awa-main.zip inspected for this project contained only a 45-byte README and awa.apk. It did not contain the original Kotlin/Java source tree, Gradle wrapper, Android Studio project files or a license file. The generated output is therefore a reverse-engineering aid, not a restored original project. Keep it separate from app/src/main until provenance, licensing, compatibility and buildability have been reviewed.
