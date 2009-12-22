package com.theusualco.L2tpTether;

import java.nio.ByteBuffer;

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
  protected boolean mHasOffset;
  protected boolean mIsPriority;
  protected short mTunnelId;
  protected short mSessionId;
  protected short mSequenceNo;
  protected short mExpectedSequenceNo;
  protected short mOffset;
  protected ByteBuffer mBuffer;
  protected ByteBuffer mPadding;

  public L2tpPacket() {
  }

  public L2tpPacket(byte[] buf) {
    init(ByteBuffer.wrap(buf));
  }

  public L2tpPacket(ByteBuffer buf) {
    init(buf);
  }

  private void init(ByteBuffer buf) {
    int flags = buf.getShort();

    assert (flags & L2TP_HEADER_MASK_VERSION) == L2TP_HEADER_VERSION;
    mIsControl = (flags & L2TP_HEADER_MASK_TYPE) != 0;
    mHasLength = (flags & L2TP_HEADER_MASK_LENGTH) != 0;
    mHasSequence = (flags & L2TP_HEADER_MASK_SEQUENCE) != 0;
    mHasOffset = (flags & L2TP_HEADER_MASK_OFFSET) != 0;
    mIsPriority = (flags & L2TP_HEADER_MASK_PRIORITY) != 0;

    int length = 0;
    if (mHasLength) {
      length = buf.getShort();
    }

    mTunnelId = buf.getShort();
    mSessionId = buf.getShort();

    if (mHasSequence) {
      mSequenceNo = buf.getShort();
      mExpectedSequenceNo = buf.getShort();
    }

    if (mHasOffset) {
      mOffset = buf.getShort();
      mPadding = buf.slice();
      mPadding.limit(mOffset);
      buf.position(buf.position() + mOffset);
    }

    if (mIsControl) {
      assert mHasLength;
      assert mHasSequence;
      assert !mHasOffset;
      assert !mIsPriority;
    }

    if (mHasLength) {
      assert buf.remaining() >= length;
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

  boolean hasPadding() {
    return mHasOffset;
  }

  boolean isPriority() {
    return mIsPriority;
  }

  int tunnelId() {
    return mTunnelId;
  }

  int sessionId() {
    return mSessionId;
  }

  int sequenceNo() {
    return mSequenceNo;
  }

  int expectedSequenceNo() {
    return mExpectedSequenceNo;
  }

  ByteBuffer padding() {
    return mPadding;
  }

  ByteBuffer payload() {
    return mBuffer;
  }

  int get(ByteBuffer dest) {
    short flags = 0;
    if (mIsControl) { flags |= L2TP_HEADER_MASK_TYPE; }
    if (mHasLength) { flags |= L2TP_HEADER_MASK_LENGTH; }
    if (mHasSequence) { flags |= L2TP_HEADER_MASK_SEQUENCE; }
    if (mHasOffset) { flags |= L2TP_HEADER_MASK_OFFSET; }
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
    if (mHasOffset) {
      dest.putShort(mOffset);
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
    dest.put(mBuffer);
  }
}
