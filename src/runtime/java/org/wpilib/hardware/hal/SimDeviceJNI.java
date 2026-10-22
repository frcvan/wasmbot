package org.wpilib.hardware.hal;

/**
 * In-memory replacement for the HAL sim device JNI.
 *
 * <p>Web Image has no HAL, so simulated devices and values live in {@link SimRegistry} instead of
 * the native HAL. {@code SimDevice}, {@code SimValue}, and the vendor sim classes that publish
 * through {@code SimDeviceSim} use this class unchanged.
 */
public class SimDeviceJNI {
  /** Input to user code from the simulator. */
  public static final int VALUE_INPUT = 0;

  /** Output from user code to the simulator. */
  public static final int VALUE_OUTPUT = 1;

  /** Bidirectional between user code and simulator. */
  public static final int VALUE_BIDIR = 2;

  /**
   * @param name device name
   * @return the device handle, or {@code 0} when the name is already in use
   */
  public static int createSimDevice(String name) {
    return SimRegistry.createDevice(name);
  }

  /**
   * @param handle device handle
   */
  public static void freeSimDevice(int handle) {
    SimRegistry.freeDevice(handle);
  }

  /**
   * @param handle device handle
   * @return the device name, or {@code null} for an unknown handle
   */
  public static String getSimDeviceName(int handle) {
    SimRegistry.Device device = SimRegistry.device(handle);
    return device == null ? null : device.name;
  }

  private static int createSimValueNative(
      int device, String name, int direction, int type, long value1, double value2) {
    return SimRegistry.createValue(device, name, direction, type, value1, value2, null, null);
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param initialValue initial value
   * @return simulated value handle, or {@code 0} for an unknown device
   */
  public static int createSimValue(int device, String name, int direction, HALValue initialValue) {
    return createSimValueNative(
        device,
        name,
        direction,
        initialValue.getType(),
        initialValue.getNativeLong(),
        initialValue.getNativeDouble());
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param initialValue initial value
   * @return simulated value handle
   */
  public static int createSimValueInt(int device, String name, int direction, int initialValue) {
    return createSimValueNative(device, name, direction, HALValue.INT, initialValue, 0.0);
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param initialValue initial value
   * @return simulated value handle
   */
  public static int createSimValueLong(int device, String name, int direction, long initialValue) {
    return createSimValueNative(device, name, direction, HALValue.LONG, initialValue, 0.0);
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param initialValue initial value
   * @return simulated value handle
   */
  public static int createSimValueDouble(
      int device, String name, int direction, double initialValue) {
    return createSimValueNative(device, name, direction, HALValue.DOUBLE, 0, initialValue);
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param options option descriptions
   * @param initialValue initial selection
   * @return simulated value handle
   */
  public static int createSimValueEnum(
      int device, String name, int direction, String[] options, int initialValue) {
    return SimRegistry.createValue(
        device, name, direction, HALValue.ENUM, initialValue, 0.0, options, null);
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param options option descriptions
   * @param optionValues option values
   * @param initialValue initial selection
   * @return simulated value handle
   */
  public static int createSimValueEnumDouble(
      int device,
      String name,
      int direction,
      String[] options,
      double[] optionValues,
      int initialValue) {
    return SimRegistry.createValue(
        device, name, direction, HALValue.ENUM, initialValue, 0.0, options, optionValues);
  }

  /**
   * @param device simulated device handle
   * @param name value name
   * @param direction input/output/bidir
   * @param initialValue initial value
   * @return simulated value handle
   */
  public static int createSimValueBoolean(
      int device, String name, int direction, boolean initialValue) {
    return createSimValueNative(
        device, name, direction, HALValue.BOOLEAN, initialValue ? 1 : 0, 0.0);
  }

  /**
   * @param handle simulated value handle
   * @return the current value
   */
  public static HALValue getSimValue(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null
        ? HALValue.makeUnassigned()
        : HALValue.fromNative(value.type, value.longValue, value.doubleValue);
  }

  /**
   * @param handle simulated value handle
   * @return the current int value
   */
  public static int getSimValueInt(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? 0 : (int) value.longValue;
  }

  /**
   * @param handle simulated value handle
   * @return the current long value
   */
  public static long getSimValueLong(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? 0 : value.longValue;
  }

  /**
   * @param handle simulated value handle
   * @return the current double value
   */
  public static double getSimValueDouble(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? 0.0 : value.doubleValue;
  }

  /**
   * @param handle simulated value handle
   * @return the current enum selection
   */
  public static int getSimValueEnum(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value == null ? 0 : (int) value.longValue;
  }

  /**
   * @param handle simulated value handle
   * @return the current boolean value
   */
  public static boolean getSimValueBoolean(int handle) {
    SimRegistry.Value value = SimRegistry.value(handle);
    return value != null && value.longValue != 0;
  }

  private static void setSimValueNative(int handle, int type, long value1, double value2) {
    SimRegistry.write(handle, type, value1, value2);
  }

  /**
   * @param handle simulated value handle
   * @param value the value to set
   */
  public static void setSimValue(int handle, HALValue value) {
    setSimValueNative(handle, value.getType(), value.getNativeLong(), value.getNativeDouble());
  }

  /**
   * @param handle simulated value handle
   * @param value the value to set
   */
  public static void setSimValueInt(int handle, int value) {
    setSimValueNative(handle, HALValue.INT, value, 0.0);
  }

  /**
   * @param handle simulated value handle
   * @param value the value to set
   */
  public static void setSimValueLong(int handle, long value) {
    setSimValueNative(handle, HALValue.LONG, value, 0.0);
  }

  /**
   * @param handle simulated value handle
   * @param value the value to set
   */
  public static void setSimValueDouble(int handle, double value) {
    setSimValueNative(handle, HALValue.DOUBLE, 0, value);
  }

  /**
   * @param handle simulated value handle
   * @param value the value to set
   */
  public static void setSimValueEnum(int handle, int value) {
    setSimValueNative(handle, HALValue.ENUM, value, 0.0);
  }

  /**
   * @param handle simulated value handle
   * @param value the value to set
   */
  public static void setSimValueBoolean(int handle, boolean value) {
    setSimValueNative(handle, HALValue.BOOLEAN, value ? 1 : 0, 0.0);
  }

  /**
   * Resets a numeric value to zero.
   *
   * @param handle simulated value handle
   */
  public static void resetSimValue(int handle) {
    SimRegistry.reset(handle);
  }

  /** Utility class. */
  private SimDeviceJNI() {}
}
