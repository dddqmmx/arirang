#!/system/bin/sh
# Arirang submodule — post-fs-data entry point.
# Runs in root mount namespace once /data is up, before zygote.
# All substantive work is delegated to focused library modules.
# Nothing in this script touches any third-party application process.

MODDIR="${0%/*}"

# Source library modules (order matters: common.sh goes first).
. "$MODDIR/lib/common.sh"
. "$MODDIR/lib/staging.sh"
. "$MODDIR/lib/vendor_bind.sh"
. "$MODDIR/lib/widevine.sh"
. "$MODDIR/lib/resetprop.sh"

# ----- phase 1: stage hook .so into tmpfs ---------------------------------
if ! arirang_staging_setup; then
    arirang_log e "arirang_post_fs_data" "staging failed; exiting"
    exit 1
fi

# ----- phase 2: bind-mount hook into a vendor-linker-visible path ----------
arirang_vendor_bind

# ----- phase 3: extract widevineDrmId for the DRM hook daemon --------------
arirang_widevine_extract

# ----- phase 4: apply system property spoofing -----------------------------
if [ -n "$ARIRANG_CONFIG_PATH" ]; then
    arirang_resetprop_apply
fi
