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

  public List<L2tpAvp> avpList = new ArrayList<L2tpAvp>();

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
                           ByteBuffer payload,
                           byte[] secret) {
    super(true,  // isControl
          true,  // hasLength
          true,  // hasSequence
          false,  // isPriority
          tunnelId, sessionId, sequenceNo, expectedSequenceNo,
          null,  // Padding
          null);  // Payload
    if (payload != null) {
      init(payload, secret);
    }
  }

  private void init(ByteBuffer src, byte[] secret) {
    byte[] random = null;
    while (src.hasRemaining()) {
      L2tpAvp avp = L2tpAvp.parse(src, secret, random);
      avpList.add(avp);

      if (avp.type == L2tpAvp.L2TP_AVP_RANDOM_VECTOR) {
        random = new byte[avp.value.limit()];
        avp.value.get(random);
      }
    }
  }

  int messageType() {
    L2tpAvp avp = avpList.get(0);
    assert avp.type == L2tpAvp.L2TP_AVP_MESSAGE_TYPE;
    assert avp.value.limit() == 2;
    return avp.value.getShort(0);
  }

  void addAvp(L2tpAvp avp) {
    if (avpList.isEmpty()) {
      assert avp.type == L2tpAvp.L2TP_AVP_MESSAGE_TYPE;
    }
    avpList.add(avp);
  }

  @Override
  void serializePayload(ByteBuffer dest) {
    for (ListIterator<L2tpAvp> it = avpList.listIterator(); it.hasNext(); ) {
      L2tpAvp avp = it.next();
      avp.serialize(dest);
    }
  }
}
