package com.theusualco.L2tpTether;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import android.util.Log;

public class HdlcFramer {
  public static final byte HDLC_SYN = (byte)0x7E;
  public static final byte HDLC_ESC = (byte)0x7D;
  public static final byte HDLC_XOR = (byte)0x20;
  public static final byte HDLC_ADDR = (byte)0xFF;

  private ByteBuffer buf = ByteBuffer.allocate(8192);
  private boolean syncSeen;

  HdlcFramer() {
    buf.position(0).limit(0);
  }

  public synchronized void put(byte[] src) {
    Log.d("HdlcFramer", "put()");
    if (buf.limit() + src.length > buf.capacity() ||
        buf.position() == buf.limit()) {
      buf.compact().flip();
      if (buf.limit() + src.length > buf.capacity()) {
        throw new BufferOverflowException();
      }
    }
    int pos = buf.position();
    buf.position(buf.limit()).limit(buf.limit() + src.length);
    buf.put(src);
    buf.position(pos);
  }

  public synchronized void getFrame(ByteBuffer dest) {
    Log.d("HdlcFramer", "getFrame");
    buf.mark();
    dest.mark();

    try {
      byte c;

      // Ignore bytes leading up to initial sync
      if (!syncSeen) {
        while ((c = buf.get()) != HDLC_SYN)
          ;
      }

      // Skip contiguous sync markers between frames
      while ((c = buf.get()) == HDLC_SYN)
        ;

      boolean escaped = false;
    loop:
      while (true) {
        switch (c) {
          case HDLC_SYN:
            break loop;
          case HDLC_ESC:
            if (!escaped) {
              escaped = true;
              break;
            }
            // fall through
          default:
            if (escaped) {
              c ^= HDLC_XOR;
              escaped = false;
            }
            dest.put(c);
            break;
        }
        c = buf.get();
      }
    } catch (BufferUnderflowException e) {
      buf.reset();
      dest.reset();
      throw e;  // rethrow
    }

    syncSeen = true;
  }
}
