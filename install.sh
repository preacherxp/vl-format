#!/usr/bin/env bash
# vl-format installer.
#
#   curl -fsSL https://raw.githubusercontent.com/purecode-projects/vl-format/main/install.sh | bash
#   curl -fsSL ... | bash -s -- v0.2.0        # pin a version
#
# Installs the fat jar to ~/.vl-format and a `vl-format` launcher into
# ~/.local/bin (override with VLFORMAT_BIN_DIR). Requires Java 17+ and curl.
set -euo pipefail

REPO="${VLFORMAT_REPO:-purecode-projects/vl-format}"
REQUESTED="${1:-latest}"
INSTALL_DIR="${VLFORMAT_HOME:-$HOME/.vl-format}"
BIN_DIR="${VLFORMAT_BIN_DIR:-$HOME/.local/bin}"
API="https://api.github.com/repos/$REPO/releases"

say() { printf '%s\n' "$*" >&2; }
die() { say "vl-format install: $*"; exit 1; }

command -v curl >/dev/null || die "curl is required"
command -v java >/dev/null || die "java is required (Java 17+)"

java_major=$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)
[ "${java_major:-0}" -ge 17 ] || die "Java 17+ required, found ${java_major:-unknown}"

if [ "$REQUESTED" = "latest" ]; then
    release_url="$API/latest"
else
    release_url="$API/tags/$REQUESTED"
fi

say "Fetching release metadata ($REQUESTED) from $REPO ..."
json=$(curl -fsSL "$release_url") || die "release '$REQUESTED' not found for $REPO"
jar_url=$(printf '%s' "$json" | grep -oE '"browser_download_url": *"[^"]*vl-format-[0-9][^"]*\.jar"' \
    | head -1 | sed -E 's/.*"(https[^"]*)"/\1/')
[ -n "$jar_url" ] || die "no vl-format jar asset in release"
tag=$(printf '%s' "$json" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]*)".*/\1/')

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
say "Downloading ${jar_url##*/} ..."
curl -fsSL -o "$INSTALL_DIR/vl-format.jar" "$jar_url"

cat > "$BIN_DIR/vl-format" <<LAUNCHER
#!/usr/bin/env bash
exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar "$INSTALL_DIR/vl-format.jar" "\$@"
LAUNCHER
chmod +x "$BIN_DIR/vl-format"

say "Installed vl-format $tag -> $BIN_DIR/vl-format"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) say "NOTE: $BIN_DIR is not on your PATH — add:  export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
"$BIN_DIR/vl-format" --version >&2 || true
