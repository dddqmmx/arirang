# Arirang submodule — shared constants and utilities
# Source this from post-fs-data.sh and service.sh.

MODDIR="${0%/*}"
INJECTOR="$MODDIR/bin/arirang_injector"
LIBDIR="$MODDIR/lib"

# ---------- staging paths ------------------------------------------------
LANDING_DIR="/dev/.arirang"
LANDING_HOOK="$LANDING_DIR/libarirang_drm_hook.so"
LANDING_ID="$LANDING_DIR/widevine_id"
LANDING_BINDPATH="$LANDING_DIR/bind_path"

# ---------- app config paths ---------------------------------------------
APP_ID="asia.nana7mi.arirang"
CONFIG_DIR_NAME="arirang-submodule"
CONFIG_FILE_NAME="config.json"

# ---------- logging ------------------------------------------------------
arirang_log() {
    local priority="$1" tag="$2" msg="$3"
    log -p "$priority" -t "$tag" "$msg"
}

# ---------- config.json helper -------------------------------------------
# Read a single key from the app's config.json via the native injector binary.
get_config_val() {
    "$INJECTOR" config "$ARIRANG_CONFIG_PATH" "$1" 2>/dev/null
}

# Resolve the app config file path (tries DE first, then CE).
resolve_config_path() {
    local found=""
    for base in /data/user_de/0 /data/user/0; do
        local candidate="$base/$APP_ID/files/$CONFIG_DIR_NAME/$CONFIG_FILE_NAME"
        if [ -f "$candidate" ]; then
            found="$candidate"
            break
        fi
    done
    ARIRANG_CONFIG_PATH="$found"
}

# Ensure injector is executable.
ensure_injector() {
    if [ ! -x "$INJECTOR" ]; then
        chmod 0755 "$INJECTOR" 2>/dev/null
    fi
}

# ---------- ensure sourced only once -------------------------------------
if [ -z "${ARIRANG_LIB_INIT:-}" ]; then
    ARIRANG_LIB_INIT=1
    ensure_injector
    resolve_config_path
fi
