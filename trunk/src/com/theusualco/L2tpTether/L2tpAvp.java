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
  static final int L2TP_AVP_RANDOM_VECTOR = 36;

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

  // Member vars
  public boolean isMandatory;
  public int type;
  public ByteBuffer value;

  public L2tpAvp(boolean mandatory, int type, ByteBuffer value) {
    init(mandatory, type, value);
  }

  public L2tpAvp(boolean mandatory, int type, byte[] value) {
    init(mandatory, type, ByteBuffer.wrap(value));
  }

  public L2tpAvp(boolean mandatory, int type, String value) {
    init(mandatory, type, ByteBuffer.wrap(EncodingUtils.getAsciiBytes(value)));
  }

  public L2tpAvp(boolean mandatory, int type, short value) {
    ByteBuffer valueBuffer = ByteBuffer.allocate(2);
    valueBuffer.putShort(value);
    init(mandatory, type, valueBuffer);
  }

  public L2tpAvp(boolean mandatory, int type, int value) {
    ByteBuffer valueBuffer = ByteBuffer.allocate(4);
    valueBuffer.putInt(value);
    init(mandatory, type, valueBuffer);
  }

  private void init(boolean mandatory, int type, ByteBuffer value) {
    this.isMandatory = mandatory;
    this.type = type;
    this.value = value;
  }

  static L2tpAvp parse(ByteBuffer src, byte[] secret, byte[] random) {
    int field = src.getShort();
    boolean mandatory = (field & L2TP_AVP_HEADER_MANDATORY_MASK) != 0;
    boolean hidden = (field & L2TP_AVP_HEADER_HIDDEN_MASK) != 0;
    int length = (field & L2TP_AVP_HEADER_LENGTH_MASK);

    assert length >= L2TP_AVP_HEADER_MINIMUM_LENGTH;
    length -= L2TP_AVP_HEADER_MINIMUM_LENGTH;
    assert src.remaining() >= length;

    int type = src.getInt();

    int position = src.position();
    int remaining = src.remaining();
    src.limit(position+length);
    ByteBuffer value = src.slice();
    src.limit(position+remaining);
    src.position(position+length);

    if (hidden) {
      unhide(value, value.slice(), type, secret, random);
      length = value.getShort();
      value.limit(length);
      value = value.slice();
    }

    return new L2tpAvp(mandatory, type, value);
  }

  private static void unhide(ByteBuffer src, ByteBuffer dest,
                             int type, byte[] secret, byte[] random) {
    assert dest.remaining() >= src.remaining();

    byte[] typeArray = {
      (byte)((type & 0xf0) >> 8),
      (byte)((type & 0x0f))
    };

    MessageDigest md = null;
    try {
      md = MessageDigest.getInstance("md5");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e.toString());
    }
    md.update(typeArray);
    md.update(secret);
    md.update(random);
    byte[] digest = md.digest();

    int startPos = src.position();
    int pos = 0;
    while (src.remaining() > 0) {
      if (pos > 0 && pos % digest.length == 0) {
        byte[] last = new byte[digest.length];
        src.position(startPos + pos - digest.length);
        src.get(last);

        md.reset();
        md.update(secret);
        md.update(last);
        digest = md.digest();
      }

      dest.put((byte)(src.get() ^ digest[pos++ % digest.length]));
    }
  }

  void serialize(ByteBuffer dest) {
    int pos = value.position();
    value.position(0);
    doSerialize(isMandatory, false, type, value, dest);
    value.position(pos);
  }

  void serializeHidden(byte[] secret, byte[] random, ByteBuffer dest) {
    ByteBuffer hiddenValue = ByteBuffer.allocate(2 + value.limit());
    hiddenValue.putShort((short)value.limit());
    unhide(value, hiddenValue, type, secret, random);
    hiddenValue.position(0);
    doSerialize(isMandatory, true, type, hiddenValue, dest);
  }

  private void doSerialize(boolean mandatory, boolean hidden, int type, ByteBuffer value,
                           ByteBuffer dest) {
    short flags = L2TP_AVP_HEADER_MINIMUM_LENGTH;
    flags += value.limit();
    flags &= L2TP_AVP_HEADER_LENGTH_MASK;
    if (mandatory) { flags |= L2TP_AVP_HEADER_MANDATORY_MASK; }
    if (hidden) { flags |= L2TP_AVP_HEADER_HIDDEN_MASK; }

    dest.putShort(flags);
    dest.putInt(type);
    dest.put(value);
  }
}
