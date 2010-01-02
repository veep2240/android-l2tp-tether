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

import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class L2tpClient implements Runnable
{
  // Message types
  public static final int TUNNEL_UP = 1;
  public static final int TUNNEL_DOWN = 2;
  public static final int SESSION_UP = 4;
  public static final int SESSION_DOWN = 8;
  public static final int SESSION_DATA = 16;

  // Control Connection States
  public static final int TUNNEL_STATE_IDLE = 0;
  public static final int TUNNEL_STATE_WAIT_CTL_REPLY = 1;
  public static final int TUNNEL_STATE_WAIT_CTL_CONN = 2;
  public static final int TUNNEL_STATE_ESTABLISHED = 3;

  // LAC Incoming Call States
  public static final int SESSION_STATE_IDLE = 0;
  public static final int SESSION_STATE_WAIT_TUNNEL = 1;
  public static final int SESSION_STATE_WAIT_REPLY = 2;
  public static final int SESSION_STATE_ESTABLISHED = 3;

  // This class only provides a single session over a single tunnel.
  private static final short LOCAL_TUNNEL_ID = 1;
  private static final short LOCAL_SESSION_ID = 1;

  private InetAddress mL2tpAddr;
  private int mL2tpPort;
  private DatagramSocket mL2tpSocket = new DatagramSocket();
  private Handler mHandler;
  private Thread mListenThread;
  private Object mTunnelLock = new Object();
  private Object mSessionLock = new Object();
  private int mTunnelState;
  private int mSessionState;
  private short mPeerTunnelId;
  private short mPeerSessionId;
  private short mSequenceNo;
  private short mExpectedSequenceNo;
  private List<L2tpPacket> mPacketSendQueue;
  private int mReceiveWindowSize;
  private int mSessionSerial;
  private boolean mSequencingRequired;

  public L2tpClient(InetAddress addr, int port) throws SocketException {
    mL2tpAddr = addr;
    mL2tpPort = port;

    init();

    mListenThread = new Thread(this);
    mListenThread.start();
  }

  private void init() {
    mTunnelState = TUNNEL_STATE_IDLE;
    mSessionState = SESSION_STATE_IDLE;
    mPeerTunnelId = 0;
    mPeerSessionId = 0;
    mSequenceNo = 0;
    mExpectedSequenceNo = 0;
    mPacketSendQueue = new ArrayList<L2tpPacket>();
    mReceiveWindowSize = 4;
    mSessionSerial = 0;
  }

  private void initSession() {
    mSessionState = SESSION_STATE_IDLE;
    mPeerSessionId = 0;
  }

  protected void finalize() throws Throwable {
    mL2tpSocket.close();

    mListenThread.join();
  }

  public void handler(Handler handler) {
    mHandler = handler;
  }

  public boolean startTunnel() {
    synchronized (mTunnelLock) {
      if (mTunnelState != TUNNEL_STATE_IDLE)
        return false;
      mTunnelState = TUNNEL_STATE_WAIT_CTL_REPLY;

      sendSCCRQ();

      try {
        mTunnelLock.wait();
      } catch (InterruptedException e) {
        Log.d("L2tpClient", "InterruptedException");
      }

      return mTunnelState == TUNNEL_STATE_ESTABLISHED;
    }
  }

  public void stopTunnel() {
    synchronized (mTunnelLock) {
      if (mTunnelState == TUNNEL_STATE_IDLE)
        return;

      sendStopCCN();

      init();
    }
  }

  public boolean startSession() {
    if (mTunnelState != TUNNEL_STATE_ESTABLISHED &&
        !startTunnel())
      return false;

    synchronized (mSessionLock) {
      if (mSessionState != SESSION_STATE_IDLE)
        return false;
      mSessionState = SESSION_STATE_WAIT_REPLY;

      sendICRQ();

      try {
        mSessionLock.wait();
      } catch (InterruptedException e) {
        Log.d("L2tpClient", "InterruptedException");
      }

      return mSessionState == SESSION_STATE_ESTABLISHED;
    }
  }

  public void stopSession() {
    synchronized (mSessionLock) {
      if (mSessionState == SESSION_STATE_IDLE)
        return;

      sendCDN();

      initSession();
    }
  }

  public void sendPacket(L2tpPacket packet) {
    packet.tunnelId(mPeerTunnelId);
    packet.sessionId(mPeerSessionId);

    boolean sequence;
    if (packet.isControl()) {
      sequence = true;
      // ZLB
      if (((L2tpControlPacket)packet).avpCount() != 0)
        sequence = false;
    } else {
      packet.sequence(mSequencingRequired);
      sequence = mSequencingRequired;
    }

    // Don't queue/reliably deliver if sequencing isn't required (ZLB, data packets).
    if (!sequence) {
      doSendPacket(packet);
      return;
    }

    packet.sequenceNo(mSequenceNo);
    mSequenceNo++;

    // Queue the packet and send it if it's in the window
    mPacketSendQueue.add(packet);
    if (mPacketSendQueue.size() < mReceiveWindowSize) {
      doSendPacket(packet);
    } else {
      Log.d("L2tpClient", "Too many queued packets, not sending");
    }
  }

  void doSendPacket(L2tpPacket packet) {
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

  private void sendMessage(int what, Object obj) {
    if (mHandler != null) {
      mHandler.sendMessage(Message.obtain(null, what, obj));
    }
  }

  private void sendMessage(int what) {
    sendMessage(what, null);
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

  void sendStopCCN() {
    L2tpControlPacket stopccn = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_StopCCN);
    stopccn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_RESULT_CODE, (short)1));

    sendPacket(stopccn);
  }

  void sendHELLO() {
    L2tpControlPacket hello = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_HELLO);

    sendPacket(hello);
  }

  void sendZLB() {
    L2tpControlPacket zlb = new L2tpControlPacket();

    sendPacket(zlb);
  }

  void sendICRQ() {
    L2tpControlPacket icrq = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_ICRQ);
    icrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_ASSIGNED_SESSION_ID, LOCAL_SESSION_ID));
    icrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_CALL_SERIAL_NUMBER, (int)mSessionSerial++));

    sendPacket(icrq);
  }

  void sendICCN() {
    L2tpControlPacket iccn = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_ICCN);
    iccn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_CONNECT_SPEED, (int)0));
    iccn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_FRAMING_TYPE, (short)0));

    sendPacket(iccn);
  }

  void sendCDN() {
    L2tpControlPacket cdn = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_CDN);
    cdn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_ASSIGNED_SESSION_ID, LOCAL_SESSION_ID));
    cdn.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_RESULT_CODE, (int)3));

    sendPacket(cdn);
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

      ByteBuffer buf = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
      L2tpPacket l2tpPacket = L2tpPacket.parse(buf);

      if (l2tpPacket.isControl()) {
        handleControlPacket((L2tpControlPacket)l2tpPacket);
      } else {
        Log.d("L2tpClient", "DATA packet");
        sendMessage(SESSION_DATA, l2tpPacket.payload());
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

    try {
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
      case L2tpControlPacket.L2TP_CTRL_TYPE_StopCCN:
        handleStopCCN(packet);
        break;
      case L2tpControlPacket.L2TP_CTRL_TYPE_HELLO:
        handleHELLO(packet);
        break;
      default:
        Log.d("L2tpClient", "unknown message type");
        break;
      }
    } catch (Exception e) {
      Log.d("L2tpClient", "caught exception in handler: " + e.getMessage());
      e.printStackTrace();
    }
  }

  void handleSCCRP(L2tpControlPacket packet) throws AvpNotFoundException {
    Log.d("L2tpClient", "handleSCCRP");

    synchronized (mTunnelLock) {
      if (mTunnelState != TUNNEL_STATE_WAIT_CTL_REPLY) {
        Log.d("L2tpClient", "not in wait-ctl-reply");
        return;
      }

      ByteBuffer version = packet.getAvp(L2tpAvp.L2TP_AVP_PROTOCOL_VERSION).attributeValue();
      if (version.limit() != 2 || version.getShort() != L2tpControlPacket.L2TP_PROTOCOL_V1_0) {
        Log.d("L2tpClient", "bad Protocol-Version");
        return;
      }

      // Optional
      try {
        ByteBuffer receiveWindowSize = packet.getAvp(L2tpAvp.L2TP_AVP_RECEIVE_WINDOW_SIZE).attributeValue();
        if (receiveWindowSize.limit() != 2) {
          Log.d("L2tpClient", "bad Receive-Window-Size");
          return;
        }
        mReceiveWindowSize = receiveWindowSize.getShort();
        if (mReceiveWindowSize <= 0) { mReceiveWindowSize = 1; }
      } catch (AvpNotFoundException e) { }

      ByteBuffer tunnelId = packet.getAvp(L2tpAvp.L2TP_AVP_ASSIGNED_TUNNEL_ID).attributeValue();
      if (tunnelId.limit() != 2) {
        Log.d("L2tpClient", "bad Assigned-Tunnel-Id");
        return;
      }

      mTunnelState = TUNNEL_STATE_ESTABLISHED;
      mPeerTunnelId = tunnelId.getShort();
      Log.d("L2tpClient", "Tunnel-Id = " + mPeerTunnelId);

      sendSCCCN();
      sendMessage(TUNNEL_UP);
      mTunnelLock.notify();
    }
  }

  void handleHELLO(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleHELLO");

    sendZLB();
  }

  void handleICRP(L2tpControlPacket packet) throws AvpNotFoundException {
    Log.d("L2tpClient", "handleICRP");

    synchronized (mSessionLock) {
      if (mSessionState != SESSION_STATE_WAIT_REPLY) {
        Log.d("L2tpClient", "not in wait-reply");
        return;
      }

      ByteBuffer sessionId = packet.getAvp(L2tpAvp.L2TP_AVP_ASSIGNED_SESSION_ID).attributeValue();
      if (sessionId.limit() != 2) {
        Log.d("L2tpClient", "bad Assigned-Session-Id");
        return;
      }

      mSessionState = SESSION_STATE_ESTABLISHED;
      mPeerSessionId = sessionId.getShort();
      Log.d("L2tpClient", "Session-Id = " + mPeerSessionId);

      sendICCN();
      sendMessage(SESSION_UP);
      mSessionLock.notify();
    }
  }

  void handleCDN(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleICRP");

    synchronized (mSessionLock) {
      mSessionState = SESSION_STATE_IDLE;
      mPeerSessionId = 0;

      sendZLB();
      sendMessage(SESSION_DOWN);
    }
  }

  void handleStopCCN(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleStopCCN");

    synchronized (mTunnelLock) {
      mTunnelState = TUNNEL_STATE_IDLE;
      mPeerTunnelId = 0;

      sendZLB();
      sendMessage(TUNNEL_DOWN);
    }
  }
}
