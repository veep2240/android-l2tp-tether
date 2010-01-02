package com.theusualco.L2tpTether;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import android.util.Log;

public class L2tpControlPacket extends L2tpPacket
{
  // Control Connection Management
  static final short L2TP_CTRL_TYPE_SCCRQ = 1;  // Start-Control-Connection-Request
  static final short L2TP_CTRL_TYPE_SCCRP = 2;  // Start-Control-Connection-Reply
  static final short L2TP_CTRL_TYPE_SCCCN = 3;  // Start-Control-Connection-Connected
  static final short L2TP_CTRL_TYPE_StopCCN = 4;  // Stop-Control-Connection-Notification
  static final short L2TP_CTRL_TYPE_HELLO = 6;  // Hello

  // Call Management
  static final short L2TP_CTRL_TYPE_OCRQ = 7;  // Outgoing-Call-Request
  static final short L2TP_CTRL_TYPE_OCRP = 8;  // Outgoing-Call-Reply
  static final short L2TP_CTRL_TYPE_OCCN = 9;  // Outgoing-Call-Connected
  static final short L2TP_CTRL_TYPE_ICRQ = 10;  // Incoming-Call-Request
  static final short L2TP_CTRL_TYPE_ICRP = 11;  // Incoming-Call-Reply
  static final short L2TP_CTRL_TYPE_ICCN = 12;  // Incoming-Call-Connected
  static final short L2TP_CTRL_TYPE_CDN = 14; // Call-Disconnect-Notify

  // Error Reporting
  static final short L2TP_CTRL_TYPE_WEN = 15;  // WAN-Error-Notify

  // PPP Session Control
  static final short L2TP_CTRL_TYPE_SLI = 16;  // Set-Link-Info

  static final short L2TP_PROTOCOL_V1_0 = 0x100;

  private List<L2tpAvp> mAvpList = new ArrayList();

  public L2tpControlPacket() {
    super();
    mIsControl = true;
    mHasLength = true;
    mHasSequence = true;
  }

  public L2tpControlPacket(short messageType) {
    super();
    mIsControl = true;
    mHasLength = true;
    mHasSequence = true;
    addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_MESSAGE_TYPE, messageType));
  }

  public L2tpControlPacket(short tunnelId,
                           short sessionId,
                           short sequenceNo,
                           short expectedSequenceNo,
                           ByteBuffer payload) {
    super(true,  // isControl
          true,  // hasLength
          true,  // hasSequence
          false,  // isPriority
          tunnelId, sessionId, sequenceNo, expectedSequenceNo,
          null,  // Padding
          null);  // Payload
    if (payload != null) {
      init(payload);
    }
  }

  private void init(ByteBuffer src) {
    while (src.hasRemaining()) {
      mAvpList.add(L2tpAvp.getL2tpAvp(src));
    }
  }

  int messageType() {
    L2tpAvp avp = mAvpList.get(0);
    assert avp.vendorId() == L2tpAvp.L2TP_AVP_IETF_VENDOR_ID;
    assert avp.attributeType() == L2tpAvp.L2TP_AVP_MESSAGE_TYPE;
    return avp.attributeValue().getShort();
  }

  void addAvp(L2tpAvp avp) {
    if (mAvpList.isEmpty()) {
      assert avp.vendorId() == L2tpAvp.L2TP_AVP_IETF_VENDOR_ID;
      assert avp.attributeType() == L2tpAvp.L2TP_AVP_MESSAGE_TYPE;
    }
    mAvpList.add(avp);
  }

  L2tpAvp getAvp(int attributeType) throws AvpNotFoundException {
    for (ListIterator<L2tpAvp> it = mAvpList.listIterator(); it.hasNext(); ) {
      L2tpAvp avp = it.next();
      if (avp.vendorId() == (short)((attributeType >> 16) & 0xffff) &&
          avp.attributeType() == (short)(attributeType & 0xffff)) {
        return avp;
      }
    }
    Log.d("L2tpControlPacket", "missing avp: " + attributeType);
    throw new AvpNotFoundException();
  }

  short getAvpShort(int attributeType) throws AvpNotFoundException, AvpFormatInvalidException {
    ByteBuffer buf = getAvp(attributeType).attributeValue();
    if (buf.limit() != 2)
      throw new AvpFormatInvalidException();
    return buf.getShort();
  }

  int getAvpInt(int attributeType) throws AvpNotFoundException, AvpFormatInvalidException {
    ByteBuffer buf = getAvp(attributeType).attributeValue();
    if (buf.limit() != 4)
      throw new AvpFormatInvalidException();
    return buf.getInt();
  }

  int avpCount() {
    return mAvpList.size();
  }

  @Override
  void getPayload(ByteBuffer dest) {
    for (ListIterator<L2tpAvp> it = mAvpList.listIterator(); it.hasNext(); ) {
      L2tpAvp avp = it.next();
      avp.get(dest);
    }
  }
}
