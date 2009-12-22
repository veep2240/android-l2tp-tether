package com.theusualco.L2tpTether;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.http.util.EncodingUtils;

public class L2tpAvp
{
  static final int L2TP_AVP_HEADER_MINIMUM_LENGTH = 6;

  // Bitfield masks
  static final int L2TP_AVP_HEADER_MANDATORY_MASK = 0x8000;
  static final int L2TP_AVP_HEADER_HIDDEN_MASK = 0x4000;
  static final int L2TP_AVP_HEADER_RESERVED_MASK = 0x3c00;
  static final int L2TP_AVP_HEADER_LENGTH_MASK = 0x03ff;

  // Vendor ID
  static final short L2TP_AVP_IETF_VENDOR_ID = 0;

  // AVPs Applicable To All Control Messages
  static final int L2TP_AVP_MESSAGE_TYPE = 0;
  static final int L2tP_AVP_RANDOM_VECTOR = 36;

  // Result and Error Codes
  static final int L2TP_AVP_RESULT_CODE = 1;

  // Control Connection Management AVPs
  static final int L2TP_AVP_PROTOCOL_VERSION = 2;
  static final int L2TP_AVP_FRAMING_CAPABILITIES = 3;
  static final int L2TP_AVP_BEARER_CAPABILITIES = 4;
  static final int L2TP_AVP_TIE_BREAKER = 5;
  static final int L2TP_AVP_FIRMWARE_REVISION = 6;
  static final int L2TP_AVP_HOST_NAME = 7;
  static final int L2TP_AVP_VENDOR_NAME = 8;
  static final int L2TP_AVP_ASSIGNED_TUNNEL_ID = 9;
  static final int L2TP_AVP_RECEIVE_WINDOW_SIZE = 10;
  static final int L2TP_AVP_CHALLENGE = 11;
  static final int L2TP_AVP_CHALLENGE_RESPONSE = 13;

  // Call Management AVPs
  static final int L2TP_AVP_CAUSE_CODE = 12;
  static final int L2TP_AVP_ASSIGNED_SESSION_ID = 14;
  static final int L2TP_AVP_CALL_SERIAL_NUMBER = 15;
  static final int L2TP_AVP_MINIMUM_BPS = 16;
  static final int L2TP_AVP_MAXIMUM_BPS = 17;
  static final int L2TP_AVP_BEARER_TYPE = 18;
  static final int L2TP_AVP_FRAMING_TYPE = 19;
  static final int L2TP_AVP_CALLED_NUMBER = 21;
  static final int L2TP_AVP_CALLING_NUMBER = 22;
  static final int L2TP_AVP_SUB_ADDRESS = 23;
  static final int L2TP_AVP_CONNECT_SPEED = 24;
  static final int L2TP_AVP_RX_CONNECT_SPEED = 38;
  static final int L2TP_AVP_PHYSICAL_CHANNEL_ID = 25;
  static final int L2TP_AVP_PRIVATE_GROUP_ID = 37;
  static final int L2TP_AVP_SEQUENCING_REQUIRED = 39;

  // Proxy LCP and Authentication AVPs
  static final int L2TP_AVP_INITIAL_LCP_CONFREQ = 26;
  static final int L2TP_AVP_LAST_SENT_LCP_CONFREQ = 27;
  static final int L2TP_AVP_LAST_RECV_LCP_CONFREQ = 28;
  static final int L2TP_AVP_PROXY_AUTHEN_TYPE = 29;
  static final int L2TP_AVP_PROXY_AUTHEN_NAME = 30;
  static final int L2TP_AVP_PROXY_AUTHEN_CHALLENGE = 31;
  static final int L2TP_AVP_PROXY_AUTHEN_ID = 32;
  static final int L2TP_AVP_PROXY_AUTHEN_RESPONSE = 33;

  // Call Status AVPs
  static final int L2TP_AVP_CALL_ERRORS = 34;
  static final int L2TP_AVP_ACCM = 35;

  private boolean mMandatory;
  private short mVendorId;
  private short mAttributeType;
  private ByteBuffer mAttributeValue;
  private byte[] mSecret;

  public L2tpAvp(boolean mandatory, int attributeType, ByteBuffer value) {
    init(mandatory, attributeType, value);
  }

  public L2tpAvp(boolean mandatory, int attributeType, byte[] value) {
    init(mandatory, attributeType, ByteBuffer.wrap(value));
  }

  public L2tpAvp(boolean mandatory, int attributeType, String value) {
    init(mandatory, attributeType, ByteBuffer.wrap(EncodingUtils.getAsciiBytes(value)));
  }

  public L2tpAvp(boolean mandatory, int attributeType, short value) {
    ByteBuffer valueBuffer = ByteBuffer.allocate(2);
    valueBuffer.putShort(value);
    init(mandatory, attributeType, valueBuffer);
  }

  public L2tpAvp(boolean mandatory, int attributeType, int value) {
    ByteBuffer valueBuffer = ByteBuffer.allocate(4);
    valueBuffer.putInt(value);
    init(mandatory, attributeType, valueBuffer);
  }

  private void init(boolean mandatory, int attributeType, ByteBuffer value) {
    mMandatory = mandatory;
    mVendorId = (short)((attributeType >> 16) & 0xffff);
    mAttributeType = (short)(attributeType & 0xffff);
    mAttributeValue = value;
  }

  static L2tpAvp getL2tpAvp(ByteBuffer src) {
    int field = src.getShort();
    boolean mandatory = (field & L2TP_AVP_HEADER_MANDATORY_MASK) != 0;
    boolean hidden = (field & L2TP_AVP_HEADER_HIDDEN_MASK) != 0;
    int length = (field & L2TP_AVP_HEADER_LENGTH_MASK);

    assert length >= L2TP_AVP_HEADER_MINIMUM_LENGTH;
    length -= L2TP_AVP_HEADER_MINIMUM_LENGTH;
    assert src.remaining() >= length;

    short vendorId = src.getShort();
    short attributeType = src.getShort();
    ByteBuffer attributeValue = src.slice();
    attributeValue.limit(length);
    src.position(src.position() + length);

    return new L2tpAvp(mandatory, ((vendorId << 16) | attributeType), attributeValue);
  }

  boolean isMandatory() {
    return mMandatory;
  }

  short vendorId() {
    return mVendorId;
  }

  short attributeType() {
    return mAttributeType;
  }

  int attributeLength() {
    return mAttributeValue.limit();
  }

  ByteBuffer attributeValue() {
    mAttributeValue.position(0);
    return mAttributeValue;
  }

  void get(ByteBuffer dest) {
    mAttributeValue.position(0);

    internalGet(mMandatory, false, mVendorId, mAttributeType, mAttributeValue, dest);
  }

  void getHidden(byte[] secret, byte[] random, ByteBuffer dest) throws NoSuchAlgorithmException {
    mAttributeValue.position(0);

    ByteBuffer value = ByteBuffer.allocate(2 + mAttributeValue.limit());
    value.putShort((short)mAttributeValue.limit());

    MessageDigest md = MessageDigest.getInstance("md5");
    byte[] attributeType = { (byte)((mAttributeType & 0xf0) >> 8), (byte)(mAttributeType & 0x0f) };
    md.update(attributeType);
    md.update(secret);
    md.update(random);
    byte[] digest = md.digest();

    for (int i = 0; i < 16 && mAttributeValue.remaining() > 0; i++) {
      value.put((byte)(mAttributeValue.get() ^ digest[i]));
    }

    while (mAttributeValue.remaining() > 0) {
      md.reset();
      md.update(secret);
      md.update(digest);
      digest = md.digest();

      for (int i = 0; i < 16 && mAttributeValue.remaining() > 0; i++) {
        value.put((byte)(mAttributeValue.get() ^ digest[i]));
      }
    }

    internalGet(mMandatory, true, mVendorId, mAttributeType, value, dest);
  }

  private void internalGet(boolean mandatory, boolean hidden, short vendorId, short attributeType, ByteBuffer value, ByteBuffer dest) {
    short flags = L2TP_AVP_HEADER_MINIMUM_LENGTH;
    flags += value.limit();
    flags &= L2TP_AVP_HEADER_LENGTH_MASK;
    if (mandatory) { flags |= L2TP_AVP_HEADER_MANDATORY_MASK; }
    if (hidden) { flags |= L2TP_AVP_HEADER_HIDDEN_MASK; }

    dest.putShort(flags);
    dest.putShort(vendorId);
    dest.putShort(attributeType);
    dest.put(value);
  }
}
