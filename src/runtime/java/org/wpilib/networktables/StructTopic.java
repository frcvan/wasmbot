package org.wpilib.networktables;

import org.wpilib.util.struct.Struct;

/** Host-backed structured NetworkTables topic. */
public final class StructTopic<T> {
  private final String name;
  private final Struct<T> struct;

  StructTopic(String name, Struct<T> struct) {
    this.name = name;
    this.struct = struct;
  }

  public StructPublisher publish() {
    return new StructPublisher(name, struct);
  }

  public StructPublisher publish(PubSubOption... options) {
    if (options.length != 0) {
      throw new UnsupportedOperationException("NT publisher options are not supported");
    }
    return publish();
  }
}
