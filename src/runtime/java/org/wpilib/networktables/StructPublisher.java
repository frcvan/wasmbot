package org.wpilib.networktables;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.teamvan.wasmbot.WasmRuntime;
import org.wpilib.util.struct.Struct;

/** Publishes packed struct values through Wasmbot's in-process NetworkTables host. */
// Keep the raw class shape aligned with WPILib's erased WASM call sites exactly.
public final class StructPublisher implements AutoCloseable {
  private static final HexFormat HEX = HexFormat.of();

  private final JSString path;
  private final Struct<?> struct;
  private final JSObject owner;
  private final ByteBuffer buffer;

  StructPublisher(String name, Struct<?> struct) {
    this.path = JSString.of(name);
    this.struct = struct;
    this.owner = publish(path, JSString.of("struct:" + struct.getTypeString()));
    this.buffer = ByteBuffer.allocate(struct.getSize()).order(ByteOrder.LITTLE_ENDIAN);
  }

  /**
   * Keeps the erased WPILib ABI explicit. The desktop WPILib API calls this method as set(Object),
   * while the WASM runtime supplies this shadow implementation.
   */
  public void set(Object value) {
    setValue(value, 0);
  }

  public void set(Object value, long timestamp) {
    setValue(value, timestamp);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void setValue(Object value, long timestamp) {
    buffer.clear();
    ((Struct) struct).pack(buffer, value);
    put(
        owner,
        path,
        timestamp == 0 ? WasmRuntime.nowNanos() / 1000.0 : timestamp / 1000.0,
        JSString.of(HEX.formatHex(buffer.array())));
  }

  @Override
  public void close() {
    unpublish(owner, path);
  }

  @JS("const owner = {}; globalThis.wasmbot.nt.publish(owner, path, type, {}); return owner;")
  private static native JSObject publish(JSString path, JSString type);

  @JS("globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native void put(JSObject owner, JSString path, double timestamp, JSString value);

  @JS("globalThis.wasmbot.nt.unpublish(owner, path);")
  private static native void unpublish(JSObject owner, JSString path);
}
