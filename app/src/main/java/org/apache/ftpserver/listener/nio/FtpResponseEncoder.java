package org.apache.ftpserver.listener.nio;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolEncoderAdapter;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

/**
 * Thread-safe replacement for the original Apache FtpServer FtpResponseEncoder.
 * The original library version used a single shared `static final CharsetEncoder`,
 * which caused severe race conditions and fatal native ICU memory corruption (SIGSEGV / SIGABRT)
 * on Android when handling concurrent requests across multiple worker threads.
 * Using ThreadLocal ensures each worker thread operates with its own isolated encoder.
 */
public class FtpResponseEncoder extends ProtocolEncoderAdapter {

    private static final ThreadLocal<CharsetEncoder> ENCODER = ThreadLocal.withInitial(
        StandardCharsets.UTF_8::newEncoder
    );

    @Override
    public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception {
        String value = message.toString();
        IoBuffer buf = IoBuffer.allocate(value.length()).setAutoExpand(true);
        CharsetEncoder encoder = ENCODER.get();
        encoder.reset();
        buf.putString(value, encoder);
        buf.flip();
        out.write(buf);
    }
}
