package com.theusualco.L2tpTether;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.lang.InterruptedException;

import android.util.Log;

public class L2tpClient implements Runnable
{
  private static final short LOCAL_TUNNEL_ID = 31337;

  private InetAddress mL2tpAddr;
  private int mL2tpPort;
  private DatagramSocket mL2tpSocket;
  private Thread mListenThread;

  private short mPeerTunnelId = 0;
  private short mSequenceNo = 0;
  private short mExpectedSequenceNo = 0;

  public L2tpClient(InetAddress addr, int port) throws SocketException {
    mL2tpAddr = addr;
    mL2tpPort = port;
    mL2tpSocket = new DatagramSocket();
  }

  void startTunnel() {
    mListenThread = new Thread(this);
    mListenThread.start();

    sendSCCRQ();
  }

  void stopTunnel() throws InterruptedException {
    mL2tpSocket.close();

    mListenThread.join();
  }

  void sendPacket(L2tpPacket packet) {
    packet.tunnelId(mPeerTunnelId);
    packet.sequenceNo(mSequenceNo++);
    packet.expectedSequenceNo(mExpectedSequenceNo);

    byte[] data = new byte[1500];
    int length = packet.get(ByteBuffer.wrap(data));

    try {
      mL2tpSocket.send(new DatagramPacket(data, length, mL2tpAddr, mL2tpPort));
    } catch (IOException e) {
      Log.d("L2tpClient", "packet send failed");
    }
  }

  void sendSCCRQ() {
    L2tpControlPacket sccrq = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_SCCRQ);
    sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_PROTOCOL_VERSION, L2tpControlPacket.L2TP_PROTOCOL_V1_0));
    sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_HOST_NAME, "hostname"));
    sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_FRAMING_CAPABILITIES, (int)0));
    sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_ASSIGNED_TUNNEL_ID, LOCAL_TUNNEL_ID));

    sendPacket(sccrq);
  }

  void sendSCCCN() {
    L2tpControlPacket scccn = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_SCCCN);

    sendPacket(scccn);
  }

  public void run() {
    while (true) {
      byte[] data = new byte[1500];
      DatagramPacket packet = new DatagramPacket(data, data.length);

      try {
        mL2tpSocket.receive(packet);
      } catch (IOException e) {
        Log.d("L2tpClient", "socket read failed");
        break;
      }

      L2tpPacket l2tpPacket = new L2tpPacket(ByteBuffer.wrap(packet.getData(), 0, packet.getLength()));

      if (l2tpPacket.isControl()) {
        handleControlPacket((L2tpControlPacket)l2tpPacket);
      }
    }
  }

  void handleControlPacket(L2tpControlPacket packet) {
    if (packet.tunnelId() != LOCAL_TUNNEL_ID) {
      Log.d("L2tpClient", "bad tunnel id");
      return;
    }

    if (packet.sequenceNo() != mExpectedSequenceNo) {
      Log.d("L2tpClient", "bad sequence #");
      return;
    }

    mExpectedSequenceNo++;

    if (packet.payloadLength() == 0) {  // ZLB
      return;
    }

    switch (packet.messageType()) {
      case L2tpControlPacket.L2TP_CTRL_TYPE_SCCRP:
        handleSCCRP(packet);
        break;
      default:
        Log.d("L2tpClient", "unknown message type");
        break;
    }
  }

  void handleSCCRP(L2tpControlPacket packet) {
    ByteBuffer version = packet.getAvp(L2tpAvp.L2TP_AVP_PROTOCOL_VERSION).attributeValue();
    if (version.limit() != 2 || version.getShort() != L2tpControlPacket.L2TP_PROTOCOL_V1_0) {
      Log.d("L2tpClient", "bad Protocol-Version");
      return;
    }

    ByteBuffer tunnelId = packet.getAvp(L2tpAvp.L2TP_AVP_ASSIGNED_TUNNEL_ID).attributeValue();
    if (tunnelId.limit() != 2) {
      Log.d("L2tpClient", "bad Assigned-Tunnel-Id");
      return;
    }

    mPeerTunnelId = tunnelId.getShort();
  }
}
