package org.wpilib.util.protobuf;

import java.io.IOException;
import java.nio.ByteBuffer;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

/**
 * Heap-buffer replacement for the SDK's protobuf serialization buffer.
 *
 * <p>The SDK allocates direct buffers, which Web Image cannot back with native memory. Sinks and
 * sources over ordinary arrays carry the same bytes; publishers copy them before the next write.
 */
public final class ProtobufBuffer<T, MessageType extends ProtoMessage<?>> {
  private ByteBuffer m_buf = ByteBuffer.allocate(1024);
  private final ProtoSink m_sink = ProtoSink.newBufferSink();
  private final ProtoSource m_source = ProtoSource.newBufferSource();
  private final MessageType m_msg;
  private final Protobuf<T, MessageType> m_proto;

  private ProtobufBuffer(Protobuf<T, MessageType> proto) {
    m_sink.setOutput(m_buf);
    m_msg = proto.createMessage();
    m_proto = proto;
  }

  public static <T, MessageType extends ProtoMessage<?>> ProtobufBuffer<T, MessageType> create(
      Protobuf<T, MessageType> proto) {
    return new ProtobufBuffer<>(proto);
  }

  public Protobuf<T, MessageType> getProto() {
    return m_proto;
  }

  public String getTypeString() {
    return m_proto.getTypeString();
  }

  public ByteBuffer write(T value) throws IOException {
    m_msg.clearQuick();
    m_proto.pack(m_msg, value);
    int size = m_msg.getSerializedSize();
    if (size > m_buf.capacity()) {
      m_buf = ByteBuffer.allocate(size * 2);
      m_sink.setOutput(m_buf);
    }
    m_sink.reset();
    m_msg.writeTo(m_sink);
    m_buf.position((int) m_sink.getTotalBytesWritten());
    return m_buf;
  }

  public T read(byte[] buf, int start, int len) throws IOException {
    m_msg.clearQuick();
    m_source.setInput(buf, start, len);
    m_msg.mergeFrom(m_source);
    return m_proto.unpack(m_msg);
  }

  public T read(byte[] buf) throws IOException {
    return read(buf, 0, buf.length);
  }

  public T read(ByteBuffer buf) throws IOException {
    m_msg.clearQuick();
    m_source.setInput(buf);
    m_msg.mergeFrom(m_source);
    return m_proto.unpack(m_msg);
  }

  public void readInto(T value, byte[] buf, int start, int len) throws IOException {
    m_msg.clearQuick();
    m_source.setInput(buf, start, len);
    m_msg.mergeFrom(m_source);
    m_proto.unpackInto(value, m_msg);
  }

  public void readInto(T value, byte[] buf) throws IOException {
    readInto(value, buf, 0, buf.length);
  }

  public void readInto(T value, ByteBuffer buf) throws IOException {
    m_msg.clearQuick();
    m_source.setInput(buf);
    m_msg.mergeFrom(m_source);
    m_proto.unpackInto(value, m_msg);
  }
}
