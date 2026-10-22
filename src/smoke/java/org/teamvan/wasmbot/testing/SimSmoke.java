package org.teamvan.wasmbot.testing;

import org.wpilib.hardware.hal.SimDevice;
import org.wpilib.hardware.hal.SimDouble;
import org.wpilib.hardware.hal.SimInt;
import org.wpilib.hardware.hal.simulation.SimDeviceDataJNI;
import org.wpilib.simulation.SimDeviceSim;

/** Exercises the in-memory HAL simulation layer that vendor simulators build on. */
final class SimSmoke {
  private SimSmoke() {}

  static void run() {
    try (SimDevice device = SimDevice.create("SimSmoke")) {
      if (device == null) {
        throw new AssertionError("the image must create simulated devices");
      }
      SimDouble output = device.createDouble("Output", SimDevice.Direction.OUTPUT, 1.5);
      SimInt mode = device.createInt("Mode", SimDevice.Direction.INPUT, 3);
      SimDeviceSim remote = new SimDeviceSim("SimSmoke");
      SimDouble remoteOutput = remote.getDouble("Output");
      SimInt remoteMode = remote.getInt("Mode");
      if (remote.getNativeHandle() != device.getNativeHandle()
          || !"SimSmoke".equals(remote.getName())) {
        throw new AssertionError("SimDeviceSim must resolve a device by name");
      }
      remoteOutput.set(2.25);
      remoteMode.set(7);
      if (output.get() != 2.25 || mode.get() != 7) {
        throw new AssertionError("sim values must be shared across handles");
      }
      if (SimDeviceDataJNI.enumerateSimValues(device.getNativeHandle()).length != 2) {
        throw new AssertionError("every created value must be enumerable");
      }
      if (SimDevice.create("SimSmoke") != null) {
        throw new AssertionError("device names must stay unique");
      }
      device.close();
      if (new SimDeviceSim("SimSmoke").getDouble("Output") != null
          || SimDevice.create("SimSmoke") == null) {
        throw new AssertionError("closing a device must free its name and values");
      }
    }
  }
}
