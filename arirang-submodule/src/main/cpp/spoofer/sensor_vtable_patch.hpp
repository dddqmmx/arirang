#pragma once

namespace arirang {

// Installs the BnSensorServer::onTransact discovery hook. The hook captures
// the live SensorService object on its first invocation and lazily patches
// its getSensorList()/getDynamicSensorList() vtable slots to route results
// through apply_rules() (sensor_rules.hpp). `libsensor` must be a valid,
// already-dlopen'd libsensor.so handle.
bool install_on_transact_hook(void *libsensor);

} // namespace arirang
