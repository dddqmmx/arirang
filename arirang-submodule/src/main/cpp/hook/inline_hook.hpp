#pragma once

#include <cstddef>

namespace arirang {

bool inline_hook_install(void *target, void *handler, void **out_trampoline);

} // namespace arirang
