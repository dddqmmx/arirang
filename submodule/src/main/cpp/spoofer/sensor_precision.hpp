#pragma once

namespace arirang {

// Installs the BitTube::sendObjects hook (sensor-event precision reduction).
// `libsensor` must be a valid, already-dlopen'd libsensor.so handle.
void install_send_objects_hook(void *libsensor);

} // namespace arirang
