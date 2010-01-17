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
import java.util.ListIterator;

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

  private InetAddress addr;
  private int port;
  private byte[] secret;
  private DatagramSocket socket = new DatagramSocket();
  private Handler handler;
  private Thread listenThread;
  private Object tunnelLock = new Object();
  private Object sessionLock = new Object();
  private int tunnelState;
  private int sessionState;
  private short peerTunnelId;
  private short peerSessionId;
  private short sequenceNo;
  private short expectedSequenceNo;
  private List<L2tpPacket> packetSendQueue;
  private int receiveWindowSize;
  private int sessionSerial;
  private boolean sequencingRequired;

  public L2tpClient(InetAddress addr, int port) throws SocketException {
    this.addr = addr;
    this.port = port;

    init();

    listenThread = new Thread(this);
    listenThread.start();
  }

  private void init() {
    tunnelState = TUNNEL_STATE_IDLE;
    sessionState = SESSION_STATE_IDLE;
    peerTunnelId = 0;
    peerSessionId = 0;
    sequenceNo = 0;
    expectedSequenceNo = 0;
    packetSendQueue = new ArrayList<L2tpPacket>();
    receiveWindowSize = 4;
    sessionSerial = 0;
  }

  private void initSession() {
    sessionState = SESSION_STATE_IDLE;
    peerSessionId = 0;
  }

  protected void finalize() throws Throwable {
    socket.close();

    listenThread.join();
  }

  public void handler(Handler handler) {
    handler = handler;
  }

  public boolean startTunnel() {
    synchronized (tunnelLock) {
      if (tunnelState == TUNNEL_STATE_ESTABLISHED)
        return true;
      if (tunnelState != TUNNEL_STATE_IDLE)
        return false;
      tunnelState = TUNNEL_STATE_WAIT_CTL_REPLY;

      sendSCCRQ();

      try {
        tunnelLock.wait();
      } catch (InterruptedException e) {
        Log.d("L2tpClient", "InterruptedException");
      }

      return tunnelState == TUNNEL_STATE_ESTABLISHED;
    }
  }

  public void stopTunnel() {
    synchronized (tunnelLock) {
      if (tunnelState == TUNNEL_STATE_IDLE)
        return;

      synchronized (sessionLock) {
        stopSession();

        sendStopCCN();

        init();
      }
    }
  }

  public boolean startSession() {
    synchronized (tunnelLock) {
      if (!startTunnel())
	return false;

      synchronized (sessionLock) {
        if (sessionState == SESSION_STATE_ESTABLISHED)
          return true;
	if (sessionState != SESSION_STATE_IDLE)
	  return false;
	sessionState = SESSION_STATE_WAIT_REPLY;

	sendICRQ();

	try {
	  sessionLock.wait();
	} catch (InterruptedException e) {
	  Log.d("L2tpClient", "InterruptedException");
	}

	return sessionState == SESSION_STATE_ESTABLISHED;
      }
    }
  }

  public void stopSession() {
    synchronized (sessionLock) {
      if (sessionState == SESSION_STATE_IDLE)
        return;

      sendCDN();

      initSession();
    }
  }

  public void sendPacket(L2tpPacket packet) {
    packet.tunnelId(peerTunnelId);
    packet.sessionId(peerSessionId);

    boolean sequence;
    if (packet.isControl()) {
      sequence = true;
      // ZLB
      if (((L2tpControlPacket)packet).avpList.size() == 0)
        sequence = false;
    } else {
      packet.sequence(sequencingRequired);
      sequence = sequencingRequired;
    }

    // Don't queue/reliably deliver if sequencing isn't required (ZLB, data packets).
    if (!sequence) {
      doSendPacket(packet);
      return;
    }

    packet.sequenceNo(sequenceNo);
    sequenceNo++;

    // Queue the packet and send it if it's in the window
    packetSendQueue.add(packet);
    if (packetSendQueue.size() < receiveWindowSize) {
      doSendPacket(packet);
    } else {
      Log.d("L2tpClient", "Too many queued packets, not sending");
    }
  }

  void doSendPacket(L2tpPacket packet) {
    Log.d("L2tpClient", "send packet");

    packet.expectedSequenceNo(expectedSequenceNo);

    byte[] data = new byte[1500];
    int length = packet.serialize(ByteBuffer.wrap(data));

    try {
      socket.send(new DatagramPacket(data, length, addr, port));
    } catch (IOException e) {
      Log.d("L2tpClient", "packet send failed");
    }
  }

  private void sendMessage(int what, Object obj) {
    if (handler != null) {
      handler.sendMessage(Message.obtain(null, what, obj));
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
    icrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_CALL_SERIAL_NUMBER, sessionSerial++));

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
        socket.receive(packet);
      } catch (IOException e) {
        Log.d("L2tpClient", "socket read failed");
        break;
      }

      ByteBuffer buf = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
      L2tpPacket l2tpPacket = L2tpPacket.parse(buf, secret);

      if (l2tpPacket.isControl()) {
        handleControlPacket((L2tpControlPacket)l2tpPacket);
      } else {
        Log.d("L2tpClient", "DATA packet");
        sendMessage(SESSION_DATA, l2tpPacket.payload());
      }
    }
  }

  void handleControlPacket(L2tpControlPacket packet) {
    Log.d("L2tpClient", "control packet: " +
          "Ns=" + packet.sequenceNo() + ", " +
          "Nr=" + packet.expectedSequenceNo());

    if (packet.tunnelId() != LOCAL_TUNNEL_ID) {
      Log.d("L2tpClient", "bad tunnel id");
      return;
    }

    Log.d("L2tpClient", "queue size: " + packetSendQueue.size());
    // Remove any acknowledged packets from the queue
    while (!packetSendQueue.isEmpty() &&
           packetSendQueue.get(0).sequenceNo() < packet.expectedSequenceNo()) {
      Log.d("L2tpClient", "removing queued entry");
      packetSendQueue.remove(0);

      // Send any new packet that has been shifted into the window
      if (packetSendQueue.size() >= receiveWindowSize) {
        Log.d("L2tpClient", "sending queued entry");
        sendPacket(packetSendQueue.get(receiveWindowSize-1));
      }
    }

    if (packet.avpList.size() == 0) {  // ZLB
      Log.d("L2tpClient", "got ZLB");
      return;
    }

    if (packet.sequenceNo() != expectedSequenceNo) {
      Log.d("L2tpClient", "bad sequence # " + packet.sequenceNo() + " != " + expectedSequenceNo);
      sendZLB();
      return;
    }

    expectedSequenceNo++;

    try {
      switch (packet.messageType()) {
        case L2tpControlPacket.L2TP_CTRL_TYPE_SCCRP:
          handleSCCRP(packet);
          break;
        case L2tpControlPacket.L2TP_CTRL_TYPE_StopCCN:
          handleStopCCN(packet);
          break;
        case L2tpControlPacket.L2TP_CTRL_TYPE_HELLO:
          handleHELLO(packet);
          break;
        case L2tpControlPacket.L2TP_CTRL_TYPE_ICRP:
          handleICRP(packet);
          break;
        case L2tpControlPacket.L2TP_CTRL_TYPE_CDN:
          handleCDN(packet);
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

  void handleSCCRP(L2tpControlPacket packet) throws AvpFormatInvalidException {
    Log.d("L2tpClient", "handleSCCRP");

    synchronized (tunnelLock) {
      if (tunnelState != TUNNEL_STATE_WAIT_CTL_REPLY) {
        Log.d("L2tpClient", "not in wait-ctl-reply");
        return;
      }

      for (ListIterator<L2tpAvp> it = packet.avpList.listIterator(); it.hasNext();) {
	L2tpAvp avp = it.next();
	switch (avp.type) {
          case L2tpAvp.L2TP_AVP_MESSAGE_TYPE:
          case L2tpAvp.L2TP_AVP_FRAMING_CAPABILITIES:
          case L2tpAvp.L2TP_AVP_HOST_NAME:
            break;
	  case L2tpAvp.L2TP_AVP_PROTOCOL_VERSION:
	    if (avp.value.limit() != 2 || avp.value.getShort(0) != L2tpControlPacket.L2TP_PROTOCOL_V1_0) {
	      Log.d("L2tpClient", "bad Protocol-Version");
	      throw new AvpFormatInvalidException();
	    }
	    break;
	  case L2tpAvp.L2TP_AVP_ASSIGNED_TUNNEL_ID:
	    if (avp.value.limit() != 2 || (peerTunnelId = avp.value.getShort(0)) == 0) {
	      Log.d("L2tpClient", "bad Tunnel-Id");
	      throw new AvpFormatInvalidException();
	    }
	    break;
	  case L2tpAvp.L2TP_AVP_RECEIVE_WINDOW_SIZE:
	    if (avp.value.limit() != 2 || (receiveWindowSize = avp.value.getShort(0)) < 1) {
	      Log.d("L2tpClient", "bad Receive-Window-Size");
	      continue;
	    }
	    break;
	  default:
	    Log.d("L2tpClient", "unknown avp type=" + avp.type);
	    if (avp.isMandatory)
	      throw new RuntimeException();  // FIXME
	    break;
	}
      }

      tunnelState = TUNNEL_STATE_ESTABLISHED;

      sendSCCCN();
      sendMessage(TUNNEL_UP);
      tunnelLock.notify();
    }
  }

  void handleHELLO(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleHELLO");

    sendZLB();
  }

  void handleICRP(L2tpControlPacket packet) throws AvpFormatInvalidException {
    Log.d("L2tpClient", "handleICRP");

    synchronized (sessionLock) {
      if (sessionState != SESSION_STATE_WAIT_REPLY) {
        Log.d("L2tpClient", "not in wait-reply");
        return;
      }

      for (ListIterator<L2tpAvp> it = packet.avpList.listIterator(); it.hasNext();) {
	L2tpAvp avp = it.next();
	switch (avp.type) {
          case L2tpAvp.L2TP_AVP_MESSAGE_TYPE:
            break;
	  case L2tpAvp.L2TP_AVP_ASSIGNED_SESSION_ID:
            if (avp.value.limit() != 2 || (peerSessionId = avp.value.getShort(0)) == 0) {
	      Log.d("L2tpClient", "bad Session-Id");
	      throw new AvpFormatInvalidException();
            }
            break;
	  default:
	    Log.d("L2tpClient", "unknown avp type=" + avp.type);
	    if (avp.isMandatory)
	      throw new RuntimeException();  // FIXME
	    break;
	}
      }

      sessionState = SESSION_STATE_ESTABLISHED;

      sendICCN();
      sendMessage(SESSION_UP);
      sessionLock.notify();
    }
  }

  void handleCDN(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleICRP");

    synchronized (sessionLock) {
      sessionState = SESSION_STATE_IDLE;
      peerSessionId = 0;

      sendZLB();
      sendMessage(SESSION_DOWN);
    }
  }

  void handleStopCCN(L2tpControlPacket packet) {
    Log.d("L2tpClient", "handleStopCCN");

    synchronized (tunnelLock) {
      tunnelState = TUNNEL_STATE_IDLE;
      peerTunnelId = 0;

      sendZLB();
      sendMessage(TUNNEL_DOWN);
    }
  }
}
