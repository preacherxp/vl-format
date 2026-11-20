#!/usr/bin/env bash
# vl-format installer.
#
#   curl -fsSL https://raw.githubusercontent.com/preacherxp/vl-format/main/install.sh | bash
#   curl -fsSL ... | bash -s -- v0.2.0        # pin a version
#
# Downloads the release jar from GitHub, checks it against the release's
# .sha256 file, installs it to ~/.vl-format (override with VLFORMAT_HOME) and
# writes a `vl-format` launcher into ~/.local/bin (override with
# VLFORMAT_BIN_DIR). No sudo, nothing else is touched. Requires Java 17+ and curl.
set -euo pipefail

TMP_JAR=""
trap 'rm -f "${TMP_JAR:-}"' EXIT

# Everything runs inside main, called on the last line, so a download cut off
# halfway through `curl | bash` runs nothing at all.
main() {
    local repo="${VLFORMAT_REPO:-preacherxp/vl-format}"
    local requested="${1:-latest}"
    local install_dir="${VLFORMAT_HOME:-$HOME/.vl-format}"
    local bin_dir="${VLFORMAT_BIN_DIR:-$HOME/.local/bin}"
    local api="https://api.github.com/repos/$repo/releases"

    command -v curl >/dev/null || die "curl is required"
    command -v java >/dev/null || die "java is required (Java 17+)"

    local java_major
    java_major=$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)
    [ "${java_major:-0}" -ge 17 ] || die "Java 17+ required, found ${java_major:-unknown}"

    local release_url
    if [ "$requested" = "latest" ]; then
        release_url="$api/latest"
    else
        release_url="$api/tags/$requested"
    fi

    say "Fetching release metadata ($requested) from $repo ..."
    local json jar_url tag
    json=$(curl -fsSL "$release_url") || die "release '$requested' not found for $repo"
    jar_url=$(printf '%s' "$json" | grep -oE '"browser_download_url": *"[^"]*vl-format-[0-9][^"]*\.jar"' \
        | head -1 | sed -E 's/.*"(https[^"]*)"/\1/')
    [ -n "$jar_url" ] || die "no vl-format jar asset in release"
    tag=$(printf '%s' "$json" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]*)".*/\1/')

    mkdir -p "$install_dir" "$bin_dir"
    TMP_JAR=$(mktemp "$install_dir/.download.XXXXXX")

    say "Downloading ${jar_url##*/} ..."
    curl -fsSL -o "$TMP_JAR" "$jar_url" || die "download failed"

    local expected actual
    expected=$(curl -fsSL "$jar_url.sha256" | awk '{print $1}') || die "no checksum file next to the jar"
    actual=$(sha256_of "$TMP_JAR")
    [ -n "$expected" ] && [ "$expected" = "$actual" ] ||
        die "checksum mismatch for ${jar_url##*/} (expected ${expected:-none}, got $actual)"
    chmod 644 "$TMP_JAR"
    mv "$TMP_JAR" "$install_dir/vl-format.jar"

    # C1-only JIT and the serial GC start fastest for a short-lived CLI.
    # VLFORMAT_PLUGINS: extra jars (colon-separated) with RuleProvider plugins.
    cat > "$bin_dir/vl-format" <<LAUNCHER
#!/usr/bin/env bash
exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \\
    -cp "$install_dir/vl-format.jar\${VLFORMAT_PLUGINS:+:\$VLFORMAT_PLUGINS}" dev.vlformat.cli.MainKt "\$@"
LAUNCHER
    chmod +x "$bin_dir/vl-format"

    say "Installed vl-format $tag -> $bin_dir/vl-format (checksum verified)"
    case ":$PATH:" in
        *":$bin_dir:"*) ;;
        *) say "NOTE: $bin_dir is not on your PATH — add:  export PATH=\"$bin_dir:\$PATH\"" ;;
    esac
    "$bin_dir/vl-format" --version >&2 || true
}

say() { printf '%s\n' "$*" >&2; }
die() { say "vl-format install: $*"; exit 1; }

sha256_of() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

main "$@"
