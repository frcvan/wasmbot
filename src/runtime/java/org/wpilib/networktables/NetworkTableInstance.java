package org.wpilib.networktables;

import java.util.HashMap;
import java.util.Map;

/**
 * Default, in-process NT instance for host-backed typed double publication.
 * Does not construct the desktop instance's native listener events or threads.
 */
public final class NetworkTableInstance implements AutoCloseable {
  public static final int DEFAULT_PORT = 5810;
  private static final NetworkTableInstance DEFAULT = new NetworkTableInstance();
  private final Map<String, Topic> topics = new HashMap<>();
  private final Map<String, DoubleTopic> doubles = new HashMap<>();

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

  public Topic getTopic(String name) {
    return topics.computeIfAbsent(name,
        key -> new Topic(this, NetworkTablesJNI.getTopic(getHandle(), key)));
  }

  public DoubleTopic getDoubleTopic(String name) {
    return doubles.computeIfAbsent(name, key -> new DoubleTopic(getTopic(key)));
  }

  /** The default instance is process-owned, as in the SDK. Close publishers to release topics. */
  @Override
  public void close() {}
}
