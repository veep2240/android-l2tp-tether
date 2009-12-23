package com.theusualco.L2tpTether;

import java.io.IOException;
import java.lang.InterruptedException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

public class L2tpClient implements Runnable
{
  private static final short LOCAL_TUNNEL_ID = 31337;

  private InetAddress mL2tpAddr;
  private int mL2tpPort;
  private DatagramSocket mL2tpSocket;
  private Thread mListenThread;

  private short mPeerTunnelId;
  private short mPeerSessionId;
  private short mSequenceNo;
  private short mExpectedSequenceNo;

  private List<L2tpPacket> mPacketSendQueue = new ArrayList();
  private int mReceiveWindowSize = 4;

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

  void startSession() {
    sendICRQ();
  }

  void stopSession() {
  }

  void maybeSendPacket(L2tpPacket packet) {
    packet.tunnelId(mPeerTunnelId);
    packet.sequenceNo(mSequenceNo);

    // Don't queue/reliably deliver data or ZLB packets
    if (!packet.isControl() || ((L2tpControlPacket)packet).avpCount() == 0) {
      sendPacket(packet);
      return;
    }

    mSequenceNo++;

    // Queue the packet and send it if it's in the window
    mPacketSendQueue.add(packet);
    if (mPacketSendQueue.size() < mReceiveWindowSize) {
      sendPacket(packet);
    } else {
      Log.d("L2tpClient", "Too many queued packets, not sending");
    }
  }

  void sendPacket(L2tpPacket packet) {
    Log.d("L2tpClient", "send packet");

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

    maybeSendPacket(sccrq);
  }

  void sendSCCCN() {
    L2tpControlPacket scccn = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_SCCCN);

    maybeSendPacket(scccn);
  }

  void sendHELLO() {
    L2tpControlPacket hello = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_HELLO);

    maybeSendPacket(hello);
  }

  void sendZLB() {
    L2tpControlPacket zlb = new L2tpControlPacket();

    maybeSendPacket(zlb);
  }

  void sendICRQ() {
    L2tpControlPacket icrq = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_ICRQ);
    icrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_ASSIGNED_SESSION_ID, (short)1));
    icrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_CALL_SERIAL_NUMBER, (int)1));

    maybeSendPacket(icrq);
  }

  void sendICCN() {
    L2tpControlPacket iccn = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_ICCN);
    iccn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_CONNECT_SPEED, (int)0));
    iccn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_FRAMING_TYPE, (short)0));

    maybeSendPacket(iccn);
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

      L2tpPacket l2tpPacket = L2tpPacket.parse(ByteBuffer.wrap(packet.getData(), 0, packet.getLength()));

      if (l2tpPacket.isControl()) {
        handleControlPacket((L2tpControlPacket)l2tpPacket);
      } else {
        Log.d("L2tpClient", "DATA packet");
      }
    }
  }

  void handleControlPacket(L2tpControlPacket packet) {
    Log.d("L2tpClient", "control packet Ns=" + packet.sequenceNo() + ", Nr=" + packet.expectedSequenceNo());

    if (packet.tunnelId() != LOCAL_TUNNEL_ID) {
      Log.d("L2tpClient", "bad tunnel id");
      return;
    }

    Log.d("L2tpClient", "queue size: " + mPacketSendQueue.size());
    // Remove any acknowledged packets from the queue
    while (!mPacketSendQueue.isEmpty() &&
           mPacketSendQueue.get(0).sequenceNo() < packet.expectedSequenceNo()) {
      Log.d("L2tpClient", "removing queued entry");
      mPacketSendQueue.remove(0);

      // Send any new packet that has been shifted into the window
      if (mPacketSendQueue.size() >= mReceiveWindowSize) {
        Log.d("L2tpClient", "sending queued entry");
        sendPacket(mPacketSendQueue.get(mReceiveWindowSize-1));
      }
    }

    if (packet.avpCount() == 0) {  // ZLB
      Log.d("L2tpClient", "got ZLB");
      return;
    }

    if (packet.sequenceNo() != mExpectedSequenceNo) {
      Log.d("L2tpClient", "bad sequence # " + packet.sequenceNo() + " != " + mExpectedSequenceNo);
      sendZLB();
      return;
    }

    mExpectedSequenceNo++;

    switch (packet.messageType()) {
      case L2tpControlPacket.L2TP_CTRL_TYPE_SCCRP:
        handleSCCRP(packet);
        break;
      case L2tpControlPacket.L2TP_CTRL_TYPE_ICRP:
        handleICRP(packet);
        break;
      case L2tpControlPacket.L2TP_CTRL_TYPE_CDN:
        handleCDN(packet);
        break;
      case L2tpControlPacket.L2TP_CTRL_TYPE_HELLO:
        handleHELLO(packet);
        break;
      default:
        Log.d("L2tpClient", "unknown message type");
        break;
    }
  }

  void handleSCCRP(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleSCCRP");

    ByteBuffer version = packet.getAvp(L2tpAvp.L2TP_AVP_PROTOCOL_VERSION).attributeValue();
    if (version.limit() != 2 || version.getShort() != L2tpControlPacket.L2TP_PROTOCOL_V1_0) {
      Log.d("L2tpClient", "bad Protocol-Version");
      return;
    }

    // Optional
    L2tpAvp rxWindowSizeAvp = packet.getAvp(L2tpAvp.L2TP_AVP_RECEIVE_WINDOW_SIZE);
    if (rxWindowSizeAvp != null) {
      ByteBuffer receiveWindowSize = rxWindowSizeAvp.attributeValue();
      if (receiveWindowSize.limit() != 2) {
        Log.d("L2tpClient", "bad Receive-Window-Size");
        return;
      }
      mReceiveWindowSize = receiveWindowSize.getShort();
      if (mReceiveWindowSize <= 0) { mReceiveWindowSize = 1; }
    }

    ByteBuffer tunnelId = packet.getAvp(L2tpAvp.L2TP_AVP_ASSIGNED_TUNNEL_ID).attributeValue();
    if (tunnelId.limit() != 2) {
      Log.d("L2tpClient", "bad Assigned-Tunnel-Id");
      return;
    }

    mPeerTunnelId = tunnelId.getShort();
    Log.d("L2tpClient", "Tunnel-Id = " + mPeerTunnelId);

    sendSCCCN();
  }

  void handleHELLO(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleHELLO");

    sendZLB();
  }

  void handleICRP(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleICRP");

    ByteBuffer sessionId = packet.getAvp(L2tpAvp.L2TP_AVP_ASSIGNED_SESSION_ID).attributeValue();
    if (sessionId.limit() != 2) {
      Log.d("L2tpClient", "bad Assigned-Session-Id");
      return;
    }

    mPeerSessionId = sessionId.getShort();
    Log.d("L2tpClient", "Session-Id = " + mPeerSessionId);

    sendICCN();
  }

  void handleCDN(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleICRP");

    sendZLB();
  }
}
