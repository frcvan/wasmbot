package org.teamvan.wasmbot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.telemetry.TelemetryBackend;
import org.wpilib.telemetry.TelemetryEntry;
import org.wpilib.telemetry.TelemetryRegistry;
import org.wpilib.util.protobuf.Protobuf;
import org.wpilib.util.struct.Struct;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;

/**
 * Typed host telemetry under /Telemetry. Structs and protobufs retain the bridge's packed lowercase
 * hex encoding; struct arrays are a single packed buffer, as on NT4. Schemas and topic properties
 * are published separately from values. Unsafe JavaScript integers retain their decimal-string
 * representation rather than silently rounding.
 */
public final class JsTelemetry implements TelemetryBackend {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final HexFormat HEX = HexFormat.of();
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  /** Registers this backend before the first telemetry log. */
  public static void install() {
    TelemetryRegistry.registerBackend("", new JsTelemetry());
  }

  @Override
  public TelemetryEntry getEntry(String path) {
    return entries.computeIfAbsent(path, Entry::new);
  }

  @Override
  public void removeEntry(String path) {
    Entry entry = entries.remove(path);
    if (entry != null) entry.close();
  }

  @Override
  public void close() {
    for (Entry entry : entries.values()) entry.close();
    entries.clear();
  }

  private static final class Entry implements TelemetryEntry {
    private final String path;
    private final JSString jsPath;
    private final JSObject owner = createOwner();
    private String type;
    private boolean closed;
    private ByteBuffer structBuffer;
    private Struct<?> structSchema;
    private boolean structArray;
    private Protobuf<?, ?> protobuf;
    private ProtoMessage<?> protobufMessage;
    private ProtoSink protobufSink;
    private ByteBuffer protobufBytes;

    private Entry(String path) {
      this.path = "/Telemetry" + (path.startsWith("/") ? path : "/" + path);
      jsPath = JSString.of(this.path);
    }

    void close() {
      closed = true;
      unpublish(owner, jsPath);
    }

    @Override
    public boolean isDiscard() {
      return closed;
    }

    @Override
    public void keepDuplicates() {
      // The in-process host preserves every write, including duplicates.
    }

    @Override
    public void setProperty(String key, String value) {
      if (!closed && !property(owner, jsPath, JSString.of(key), JSString.of(value)))
        System.err.println("Invalid telemetry property JSON at " + path + ": " + key);
    }

    private boolean prepare(String typeString) {
      if (closed) return false;
      if (type == null) {
        type = typeString;
        publish(owner, jsPath, JSString.of(typeString));
      } else if (!type.equals(typeString)) {
        System.err.println("Telemetry type mismatch at " + path + ": " + type + " vs " + typeString);
        return false;
      }
      return true;
    }
    private boolean prepareStruct(Struct<?> struct, boolean array) {
      if (closed) return false;
      if (structSchema == struct && structArray == array) return true;
      if (!prepare(struct.getTypeString() + (array ? "[]" : ""))) return false;
      NetworkTableInstance.getDefault().addSchema(struct);
      structSchema = struct;
      structArray = array;
      return true;
    }


    @Override
    public <T> void logStruct(T value, Struct<? super T> struct, long timestamp) {
      if (!prepareStruct(struct, false)) return;
      ByteBuffer buffer = structBuffer(struct.getSize());
      struct.pack(buffer, value);
      put(HEX.formatHex(buffer.array(), 0, buffer.limit()), timestamp);
    }

    @Override
    public <T> void logStructArray(T[] value, Struct<? super T> struct, long timestamp) {
      if (!prepareStruct(struct, true)) return;
      ByteBuffer buffer = structBuffer(Math.multiplyExact(struct.getSize(), value.length));
      for (T element : value) struct.pack(buffer, element);
      put(HEX.formatHex(buffer.array(), 0, buffer.limit()), timestamp);
    }

    @Override
    public <T> void logProtobuf(T value, Protobuf<? super T, ?> proto, long timestamp) {
      if (closed) return;
      if (protobuf != proto) {
        if (!prepare(proto.getTypeString())) return;
        NetworkTableInstance.getDefault().addSchema(proto);
      }
      writeProtobuf(value, proto, timestamp);
    }

    private <T, M extends ProtoMessage<?>> void writeProtobuf(
        T value, Protobuf<T, M> proto, long timestamp) {
      if (protobuf != proto) {
        protobuf = proto;
        protobufMessage = proto.createMessage();
        protobufSink = ProtoSink.newBufferSink();
      }
      @SuppressWarnings("unchecked")
      M message = (M) protobufMessage;
      message.clearQuick();
      proto.pack(message, value);
      int size = message.getSerializedSize();
      if (protobufBytes == null || protobufBytes.capacity() < size)
        protobufBytes = ByteBuffer.allocate(Math.max(128, size * 2));
      protobufBytes.clear();
      protobufSink.setOutput(protobufBytes);
      try {
        message.writeTo(protobufSink);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to serialize protobuf at " + path, e);
      }
      put(HEX.formatHex(protobufBytes.array(), 0, size), timestamp);
    }

    @Override
    public void logBoolean(boolean value, long timestamp) {
      if (prepare("boolean")) putBoolean(owner, jsPath, micros(timestamp), value);
    }

    @Override
    public void logLong(long value, long timestamp) {
      if (!prepare("int")) return;
      if (isSafeInteger(value)) putNumber(owner, jsPath, micros(timestamp), value);
      else put(Long.toString(value), timestamp);
    }

    @Override
    public void logFloat(float value, long timestamp) {
      if (prepare("float")) putNumber(owner, jsPath, micros(timestamp), value);
    }

    @Override
    public void logDouble(double value, long timestamp) {
      if (prepare("double")) putNumber(owner, jsPath, micros(timestamp), value);
    }

    @Override
    public void logString(String value, String typeString, long timestamp) {
      if (prepare(typeString)) put(value, timestamp);
    }

    @Override
    public void logBooleanArray(boolean[] value, long timestamp) {
      if (!prepare("boolean[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) setArrayElement(array, i, value[i]);
      put(array, timestamp);
    }

    @Override
    public void logShortArray(short[] value, long timestamp) {
      if (!prepare("int[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) setArrayElement(array, i, value[i]);
      put(array, timestamp);
    }

    @Override
    public void logIntArray(int[] value, long timestamp) {
      if (!prepare("int[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) setArrayElement(array, i, value[i]);
      put(array, timestamp);
    }

    @Override
    public void logLongArray(long[] value, long timestamp) {
      if (!prepare("int[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) {
        if (isSafeInteger(value[i])) setArrayElement(array, i, (double) value[i]);
        else setArrayElement(array, i, Long.toString(value[i]));
      }
      put(array, timestamp);
    }

    @Override
    public void logFloatArray(float[] value, long timestamp) {
      if (!prepare("float[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) setArrayElement(array, i, value[i]);
      put(array, timestamp);
    }

    @Override
    public void logDoubleArray(double[] value, long timestamp) {
      if (!prepare("double[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) setArrayElement(array, i, value[i]);
      put(array, timestamp);
    }

    @Override
    public void logStringArray(String[] value, long timestamp) {
      if (!prepare("string[]")) return;
      JSObject array = createArray(value.length);
      for (int i = 0; i < value.length; i++) setArrayElement(array, i, value[i]);
      put(array, timestamp);
    }

    @Override
    public void logRaw(byte[] value, String typeString, long timestamp) {
      if (prepare(typeString)) put(HEX.formatHex(value), timestamp);
    }

    private ByteBuffer structBuffer(int size) {
      if (structBuffer == null || structBuffer.capacity() < size)
        structBuffer = ByteBuffer.allocate(Math.max(128, size)).order(ByteOrder.LITTLE_ENDIAN);
      structBuffer.clear();
      structBuffer.limit(size);
      return structBuffer;
    }

    private void put(String value, long timestamp) {
      putString(owner, jsPath, micros(timestamp), JSString.of(value));
    }

    private void put(JSObject value, long timestamp) {
      putArray(owner, jsPath, micros(timestamp), value);
    }
  }

  private static double micros(long timestamp) {
    return (timestamp == 0 ? JsBridge.nowNanos() : (double) timestamp) / 1000;
  }

  private static boolean isSafeInteger(long value) {
    return value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER;
  }

  @JS("return {properties: {}, published: false};")
  private static native JSObject createOwner();

  @JS("globalThis.wasmbot.nt.publish(owner, path, type, owner.properties); owner.published = true;")
  private static native void publish(JSObject owner, JSString path, JSString type);

  @JS.Coerce
  @JS("let parsed; try { parsed = JSON.parse(value); } catch { return false; }"
      + " owner.properties[key] = parsed;"
      + " if (owner.published) globalThis.wasmbot.nt.setProperties(path, {[key]: parsed}); return true;")
  private static native boolean property(JSObject owner, JSString path, JSString key, JSString value);

  @JS("globalThis.wasmbot.nt.unpublish(owner, path);")
  private static native void unpublish(JSObject owner, JSString path);

  @JS.Coerce
  @JS("globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native void putNumber(JSObject owner, JSString path, double timestamp, double value);

  @JS.Coerce
  @JS("globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native void putBoolean(JSObject owner, JSString path, double timestamp, boolean value);

  @JS("globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native void putString(JSObject owner, JSString path, double timestamp, JSString value);

  @JS("globalThis.wasmbot.nt.put(owner, path, timestamp, value);")
  private static native void putArray(JSObject owner, JSString path, double timestamp, JSObject value);

  @JS.Coerce
  @JS("return new Array(length);")
  private static native JSObject createArray(int length);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void setArrayElement(JSObject array, int index, boolean value);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void setArrayElement(JSObject array, int index, double value);

  @JS.Coerce
  @JS("array[index] = value;")
  private static native void setArrayElement(JSObject array, int index, String value);
}
