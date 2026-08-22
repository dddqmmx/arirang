#pragma once

namespace arirang {

// Launch a detached thread that, shortly after postAppSpecialize returns,
// scrubs the framework's app-side residue (in-heap ELF copies and loader
// path strings) from the process's anonymous heap. The thread's code lives
// in an anonymous RX region, so it survives the module's dlclose. Safe to
// call once per process; must be called BEFORE the DLCLOSE option is set.
void launch_residue_scavenger();

}  // namespace arirang
