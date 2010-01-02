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

  private ByteBuffer mBuf = ByteBuffer.allocate(8192);
  private boolean mSyncSeen;

  HdlcFramer() {
    mBuf.position(0).limit(0);
  }

  public synchronized void put(byte[] src) {
    Log.d("HdlcFramer", "put()");
    if (mBuf.limit() + src.length > mBuf.capacity() ||
        mBuf.position() == mBuf.limit()) {
      mBuf.compact().flip();
      if (mBuf.limit() + src.length > mBuf.capacity()) {
        throw new BufferOverflowException();
      }
    }
    int pos = mBuf.position();
    mBuf.position(mBuf.limit()).limit(mBuf.limit() + src.length);
    mBuf.put(src);
    mBuf.position(pos);
  }

  public synchronized void getFrame(ByteBuffer dest) {
    Log.d("HdlcFramer", "getFrame");
    mBuf.mark();
    dest.mark();

    try {
      byte c;

      // Ignore bytes leading up to initial sync
      if (!mSyncSeen) {
        while ((c = mBuf.get()) != HDLC_SYN)
          ;
        mSyncSeen = true;
      }

      // Skip contiguous sync markers between frames
      while ((c = mBuf.get()) == HDLC_SYN)
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
        c = mBuf.get();
      }
    } catch (BufferUnderflowException e) {
      mBuf.reset();
      dest.reset();
      throw e;  // rethrow
    }
  }
}
