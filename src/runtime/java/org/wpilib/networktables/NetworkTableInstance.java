package org.wpilib.networktables;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.wpilib.util.protobuf.Protobuf;
import org.wpilib.util.struct.Struct;

/**
 * Default, in-process NT instance for host-backed typed publication. Does not construct the desktop
 * instance's native listener events or threads.
 */
public final class NetworkTableInstance implements AutoCloseable {
  public static final int DEFAULT_PORT = 5810;
  private static final NetworkTableInstance DEFAULT = new NetworkTableInstance();
  private final Map<String, Topic> topics = new HashMap<>();
  private final Map<String, DoubleTopic> doubles = new HashMap<>();
  private final Map<String, IntegerTopic> integers = new HashMap<>();
  private final Map<String, NetworkTable> tables = new HashMap<>();
  private final Map<String, StructTopic<?>> structs = new HashMap<>();
  private final Set<String> schemas = new HashSet<>();

  private NetworkTableInstance() {}

  public static NetworkTableInstance getDefault() {
    return DEFAULT;
  }

  public int getHandle() {
    return NetworkTablesJNI.getDefaultInstance();
  }

  public boolean isValid() {
    return true;
  }
  public NetworkTable getTable(String key) {
    String normalized = NetworkTable.normalizeKey(key);
    if (normalized.equals("/")) normalized = "";
    else if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return tables.computeIfAbsent(normalized, name -> new NetworkTable(this, name));
  }


  public Topic getTopic(String name) {
    return topics.computeIfAbsent(
        name, key -> new Topic(this, NetworkTablesJNI.getTopic(getHandle(), key)));
  }

  public DoubleTopic getDoubleTopic(String name) {
    return doubles.computeIfAbsent(name, key -> new DoubleTopic(getTopic(key)));
  }
  public IntegerTopic getIntegerTopic(String name) {
    return integers.computeIfAbsent(name, key -> new IntegerTopic(getTopic(key)));
  }


  public <T> StructTopic<T> getStructTopic(String name, Struct<T> struct) {
    @SuppressWarnings("unchecked")
    StructTopic<T> topic = (StructTopic<T>) structs.get(name);
    if (topic == null || !topic.getStruct().equals(struct)) {
      topic = StructTopic.wrap(getTopic(name), struct);
      structs.put(name, topic);
    }
    return topic;
  }

  /** Publishes the struct and its nested schemas once for host-side consumers. */
  public void addSchema(Struct<?> struct) {
    String type = struct.getTypeString();
    if (schemas.contains(type)) return;
    NetworkTablesJNI.addStructSchema(type, struct.getSchema());
    schemas.add(type);
    for (Struct<?> nested : struct.getNested()) {
      addSchema(nested);
    }
  }
  public boolean hasSchema(String name) {
    return schemas.contains(name);
  }

  public void addSchema(String name, String type, String schema) {
    addSchema(name, type, schema.getBytes(StandardCharsets.UTF_8));
  }

  public void addSchema(String name, String type, byte[] schema) {
    if (schemas.add(name)) NetworkTablesJNI.addSchema(name, type, schema);
  }

  public void addSchema(Protobuf<?, ?> proto) {
    proto.forEachDescriptor(this::hasSchema,
        (name, schema) -> addSchema(name, "proto:FileDescriptor", schema));
  }


  /** The default instance is process-owned, as in the SDK. Close publishers to release topics. */
  @Override
  public void close() {}
}
