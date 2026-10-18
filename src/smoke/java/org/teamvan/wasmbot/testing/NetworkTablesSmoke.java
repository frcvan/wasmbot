package org.teamvan.wasmbot.testing;

import java.util.function.Consumer;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.StructPublisher;

/**
 * SDK interface dispatch, struct packing, timestamp ordering, defaults, and publisher lifetimes.
 */
final class NetworkTablesSmoke {
  private NetworkTablesSmoke() {}

  static void run() {
    var instance = NetworkTableInstance.getDefault();
    var first = new Pose2d(1.25, -2.5, new Rotation2d(0.5));
    var second = new Pose2d(3, 4, new Rotation2d(-1));
    var current = instance.getStructTopic("/Review/NTStructCurrent", Pose2d.struct);
    try (StructPublisher<Pose2d> publisher = current.publish()) {
      if (publisher.getTopic() != current
          || publisher.getTopic().getStruct() != Pose2d.struct
          || !publisher.isValid()
          || publisher.getHandle() == 0) {
        throw new AssertionError("SDK struct publisher must expose its topic and native handle");
      }
      publisher.setDefault(first);
      publisher.setDefault(second);
      Consumer<Pose2d> consumer = publisher;
      consumer.accept(second);
    }

    var timed = instance.getStructTopic("/Review/NTStructTimed", Pose2d.struct);
    try (StructPublisher<Pose2d> publisher = timed.publish();
        StructPublisher<Pose2d> peer = timed.publish()) {
      if (publisher.getHandle() == peer.getHandle()) {
        throw new AssertionError("Struct publishers must own independent native handles");
      }
      publisher.set(first, 2_000_000_000L);
      publisher.set(second, 1_000_000_000L);
      peer.setDefault(second);
      publisher.close();
      publisher.set(second, 3_000_000_000L);
      peer.set(second, 4_000_000_000L);
    }
    try (StructPublisher<Pose2d> reopened = timed.publish()) {
      reopened.setDefault(first);
    }
  }
}
