package org.teamvan.wasmbot.testing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Consumer;
import org.graalvm.webimage.api.JS;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.NetworkTableType;
import org.wpilib.networktables.NetworkTablesJNI;
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
    checkBinaryRanges(instance);
  }

  private static void checkBinaryRanges(NetworkTableInstance instance) {
    int topic = NetworkTablesJNI.getTopic(1, "/Review/BinaryRanges");
    int publisher = NetworkTablesJNI.publish(topic, NetworkTableType.RAW.getValue(), "raw");
    try {
      byte[] words = new byte[131072];
      for (int word = 0; word < 65536; word++) {
        words[word * 2] = (byte) word;
        words[word * 2 + 1] = (byte) (word >>> 8);
      }
      NetworkTablesJNI.setRaw(publisher, 1000, words);
      Arrays.fill(words, (byte) 0);
      if (!allWords())
        throw new AssertionError("Binary carrier corrupted UTF-16 words or aliased source");

      ByteBuffer direct = ByteBuffer.allocateDirect(16);
      for (int i = 0; i < 16; i++) direct.put(i, (byte) (i * 17));
      direct.limit(12).position(9).order(ByteOrder.BIG_ENDIAN);
      NetworkTablesJNI.setRaw(publisher, 2000, direct, 2, 7);
      direct.put(2, (byte) 0);
      checkSample(34, 51, 68, 85, 102, 119, 136);
      if (direct.position() != 9 || direct.limit() != 12 || direct.order() != ByteOrder.BIG_ENDIAN)
        throw new AssertionError("Raw publication changed caller buffer state");

      ByteBuffer readOnly = direct.asReadOnlyBuffer();
      NetworkTablesJNI.setRaw(publisher, 3000, readOnly, 3, 3);
      checkSample(51, 68, 85);
      if (readOnly.position() != 9 || readOnly.limit() != 12)
        throw new AssertionError("Raw publication changed read-only buffer state");

      byte[] source = {99, 0, -1, -128, 127, 88};
      ByteBuffer slice = ByteBuffer.wrap(source).position(1).limit(5).slice();
      NetworkTablesJNI.setRaw(publisher, 4000, slice, 1, 3);
      Arrays.fill(source, (byte) 0);
      checkSample(255, 128, 127);
      try {
        NetworkTablesJNI.setRaw(publisher, 5000, slice, 3, 2);
        throw new AssertionError("Raw range exceeded buffer limit");
      } catch (IndexOutOfBoundsException expected) {
        checkSample(255, 128, 127);
      }

      NetworkTablesJNI.setRaw(publisher, 6000, source, source.length, 0);
      checkSample();
      NetworkTablesJNI.setRaw(publisher, 7000, new byte[] {-1});
      checkSample(255);
      NetworkTablesJNI.setRaw(publisher, 8000, new byte[] {0, -128, -1, 127});
      checkSample(0, 128, 255, 127);
      instance.addSchema("ReviewBinary", "proto:FileDescriptor", new byte[] {0, -1, -128, 1});
      if (!schemaBytes()) throw new AssertionError("Binary schema bytes or timestamp changed");
    } finally {
      NetworkTablesJNI.release(publisher);
    }
  }

  private static void checkSample(int... expected) {
    if (!sampleEquals(expected))
      throw new AssertionError("Binary sample range or length corrupted");
  }

  @JS.Coerce
  @JS(
      """
      const bytes = globalThis.wasmbot.ntGet('/Review/BinaryRanges');
      if (!(bytes instanceof Uint8Array) || bytes.length !== expected.length) return false;
      for (let i = 0; i < bytes.length; i++) if (bytes[i] !== expected[i]) return false;
      return true;
      """)
  private static native boolean sampleEquals(int[] expected);

  @JS.Coerce
  @JS(
      """
      const bytes = globalThis.wasmbot.ntGet('/Review/BinaryRanges');
      if (!(bytes instanceof Uint8Array) || bytes.length !== 131072) return false;
      for (let word = 0; word < 65536; word++) {
        if (bytes[word * 2] !== (word & 255) || bytes[word * 2 + 1] !== (word >>> 8)) return false;
      }
      return true;
      """)
  private static native boolean allWords();

  @JS.Coerce
  @JS(
      """
      const topic = globalThis.wasmbot.nt.getTopic('/.schema/ReviewBinary');
      return topic.timestamp === 0 && topic.value instanceof Uint8Array
        && topic.value.length === 4 && topic.value[0] === 0 && topic.value[1] === 255
        && topic.value[2] === 128 && topic.value[3] === 1;
      """)
  private static native boolean schemaBytes();
}
