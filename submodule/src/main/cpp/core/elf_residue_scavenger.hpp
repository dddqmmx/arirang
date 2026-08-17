#pragma once

namespace arirang {

// Launch a detached thread that, shortly after postAppSpecialize returns,
// scrubs the framework's app-side residue (in-heap ELF copies and loader path
// strings) from the process's anonymous heap. Safe to call once per process.
void launch_residue_scavenger();

}  // namespace arirang
