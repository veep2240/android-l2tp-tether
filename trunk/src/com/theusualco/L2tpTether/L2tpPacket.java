package com.theusualco.L2tpTether;

import java.nio.ByteBuffer;

import android.util.Log;

public class L2tpPacket
{
  static final int L2TP_HEADER_VERSION = 2;

  static final int L2TP_HEADER_MASK_TYPE = 0x8000;
  static final int L2TP_HEADER_MASK_LENGTH = 0x4000;
  static final int L2TP_HEADER_MASK_SEQUENCE = 0x0800;
  static final int L2TP_HEADER_MASK_OFFSET = 0x0200;
  static final int L2TP_HEADER_MASK_PRIORITY = 0x0100;
  static final int L2TP_HEADER_MASK_VERSION = 0x000f;

  protected boolean mIsControl;
  protected boolean mHasLength;
  protected boolean mHasSequence;
  protected boolean mIsPriority;
  protected short mTunnelId;
  protected short mSessionId;
  protected short mSequenceNo;
  protected short mExpectedSequenceNo;
  protected ByteBuffer mPadding;
  protected ByteBuffer mPayload;

  public L2tpPacket() {
  }

  public L2tpPacket(boolean isControl,
                    boolean hasLength,
                    boolean hasSequence,
                    boolean isPriority,
                    short tunnelId,
                    short sessionId,
                    short sequenceNo,
                    short expectedSequenceNo,
                    ByteBuffer padding,
                    ByteBuffer payload) {
    mIsControl = isControl;
    mHasLength = hasLength;
    mHasSequence = hasSequence;
    mIsPriority = isPriority;
    mTunnelId = tunnelId;
    mSessionId = sessionId;
    mSequenceNo = sequenceNo;
    mExpectedSequenceNo = expectedSequenceNo;
    mPadding = padding;
    mPayload = payload;
  }

  static public L2tpPacket parse(ByteBuffer buf) {
    int startOffset = buf.position();
    int length = buf.remaining();

    int flags = buf.getShort();
    if ((flags & L2TP_HEADER_MASK_VERSION) != L2TP_HEADER_VERSION) {
      Log.d("L2tpPacket", "bad l2tp version");
      return null;
    }
    boolean isControl = (flags & L2TP_HEADER_MASK_TYPE) != 0;
    boolean hasLength = (flags & L2TP_HEADER_MASK_LENGTH) != 0;
    boolean hasSequence = (flags & L2TP_HEADER_MASK_SEQUENCE) != 0;
    boolean hasOffset = (flags & L2TP_HEADER_MASK_OFFSET) != 0;
    boolean isPriority = (flags & L2TP_HEADER_MASK_PRIORITY) != 0;

    if (hasLength) {
      int newLength = buf.getShort();
      if (newLength > length) {
        Log.d("L2tpPacket", "bad length");
        return null;
      }
      length = newLength;
    }

    short tunnelId = buf.getShort();
    short sessionId = buf.getShort();

    short sequenceNo = 0;
    short expectedSequenceNo = 0;
    if (hasSequence) {
      sequenceNo = buf.getShort();
      expectedSequenceNo = buf.getShort();
    }

    ByteBuffer padding = null;
    if (hasOffset) {
      short offsetLength = buf.getShort();
      padding = buf.slice();
      padding.limit(offsetLength);
      buf.position(buf.position() + offsetLength);
    }

    ByteBuffer payload = null;
    int payloadLength = length - (buf.position() - startOffset);
    if (payloadLength > 0) {
      payload = buf.slice();
      payload.limit(payloadLength);
      buf.position(buf.position() + payloadLength);
    }

    if (isControl) {
      if (!hasLength || !hasSequence || padding != null || isPriority) {
        Log.d("L2tpPacket", "bad fields in control packet");
        return null;
      }

      return new L2tpControlPacket(tunnelId, sessionId, sequenceNo, expectedSequenceNo, payload);
    } else {
      return new L2tpPacket(isControl, hasLength, hasSequence, isPriority,
                            tunnelId, sessionId, sequenceNo, expectedSequenceNo,
                            padding, payload);
    }
  }

  boolean isControl() {
    return mIsControl;
  }

  boolean isData() {
    return !mIsControl;
  }

  boolean hasLength() {
    return mHasLength;
  }

  boolean hasSequence() {
    return mHasSequence;
  }

  void sequence(boolean sequence) {
    mHasSequence = sequence;
  }

  boolean hasPadding() {
    return mPadding != null;
  }

  boolean isPriority() {
    return mIsPriority;
  }

  short tunnelId() {
    return mTunnelId;
  }

  void tunnelId(short tunnelId) {
    mTunnelId = tunnelId;
  }

  short sessionId() {
    return mSessionId;
  }

  void sessionId(short sessionId) {
    mSessionId = sessionId;
  }

  short sequenceNo() {
    return mSequenceNo;
  }

  void sequenceNo(short sequenceNo) {
    mSequenceNo = sequenceNo;
  }

  short expectedSequenceNo() {
    return mExpectedSequenceNo;
  }

  void expectedSequenceNo(short expectedSequenceNo) {
    mExpectedSequenceNo = expectedSequenceNo;
  }

  ByteBuffer padding() {
    mPadding.position(0);
    return mPadding;
  }

  int paddingLength() {
    return mPadding.limit();
  }

  ByteBuffer payload() {
    mPayload.position(0);
    return mPayload;
  }

  int payloadLength() {
    return mPayload.limit();
  }

  int get(ByteBuffer dest) {
    short flags = 0;
    if (mIsControl) { flags |= L2TP_HEADER_MASK_TYPE; }
    if (mHasLength) { flags |= L2TP_HEADER_MASK_LENGTH; }
    if (mHasSequence) { flags |= L2TP_HEADER_MASK_SEQUENCE; }
    if (mPadding != null) { flags |= L2TP_HEADER_MASK_OFFSET; }
    if (mIsPriority) { flags |= L2TP_HEADER_MASK_PRIORITY; }
    flags |= L2TP_HEADER_VERSION;

    int startOffset = dest.position();
    dest.putShort(flags);
    int lengthOffset = dest.position();
    if (mHasLength) {
      dest.putShort((short)0);  // Length, will fill in later
    }
    dest.putShort(mTunnelId);
    dest.putShort(mSessionId);
    if (mHasSequence) {
      dest.putShort(mSequenceNo);
      dest.putShort(mExpectedSequenceNo);
    }
    if (mPadding != null) {
      dest.putShort((short)mPadding.limit());
      dest.put(mPadding);
    }

    getPayload(dest);

    // Fill in the length field
    int endOffset = dest.position();
    if (mHasLength) {
      dest.position(lengthOffset);
      dest.putShort((short)(endOffset - startOffset));
      dest.position(endOffset);
    }

    return endOffset - startOffset;
  }

  void getPayload(ByteBuffer dest) {
    dest.put(mPayload);
  }
}
