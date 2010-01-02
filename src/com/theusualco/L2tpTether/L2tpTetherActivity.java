package com.theusualco.L2tpTether;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.NullPointerException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.util.UUID;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.telephony.TelephonyManager;
import android.content.Context;

public class L2tpTetherActivity extends Activity implements Runnable
{
  private static final String NAME = "L2TP Tether";
  private static final UUID UUID_SERIAL_PORT_PROFILE = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  private static final UUID UUID_DUN_PROFILE = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB");

  private static final String L2TP_HOST = "lns.theusualco.com";
  private static final int L2TP_PORT = 1701;

  private InetAddress mL2tpAddr;
  private L2tpClient mL2tpClient;
  private BluetoothAdapter mAdapter;
  private BluetoothServerSocket mServer;
  private InputStream mInStream;
  private OutputStream mOutStream;

  private Handler mHandler = new Handler() {
    public void handleMessage(Message msg) {
      Log.d("L2tpTetherActivity", "handleMessage");
      if (msg.what == L2tpClient.SESSION_DATA) {
        try {
          mOutStream.write(((ByteBuffer)msg.obj).array());
        } catch (IOException e) {
          Log.d("L2tpTetherActivity", "write failed: " + e.getMessage());
        }
      }
    }
  };

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    makeTunnel();
    makeDiscoverable();
    makeListenSocket();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    Log.d("L2tpTetherActivity", "onKeyDown");
    Thread thread = new Thread(this);
    thread.start();
    return super.onKeyDown(keyCode, event);
  }

  public void run() {
    Log.d("L2tpTetherActivity", "run");

    try {
      acceptClientConnection();
    } catch (IOException e) {
      Log.d("L2tpTetherActivity", "IOException: " + e.getMessage());
    }

    mL2tpClient.stopSession();
  }

  boolean makeTunnel() {
    try {
      mL2tpAddr = InetAddress.getByName(L2TP_HOST);
    } catch (UnknownHostException e) {
      Log.d("L2tpTetherActivity", "resolve failed");
      return false;
    }

    try {
      mL2tpClient = new L2tpClient(mL2tpAddr, L2TP_PORT);
    } catch (SocketException e) {
      Log.d("L2tpTetherActivity", "creating client failed");
      return false;
    }

    mL2tpClient.handler(mHandler);

    if (!mL2tpClient.startTunnel())
      return false;

    return true;
  }

  void makeDiscoverable() {
    Log.d("L2tpTetherActivity", "makeDiscoverable");
    Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
    discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
    startActivity(discoverableIntent);
  }

  boolean makeListenSocket() {
    Log.d("L2tpTetherActivity", "makeListenSocket");
    mAdapter = BluetoothAdapter.getDefaultAdapter();

    if (mServer != null) {
      try {
        mServer.close();
      } catch (IOException e) { }
      mServer = null;
    }

    try {
      mServer = mAdapter.listenUsingRfcommWithServiceRecord(NAME, UUID_DUN_PROFILE);
    } catch (IOException e) {
      Log.d("L2tpTetherActivity", "IOException in listen: " + e.getMessage());
      return false;
    }

    return true;
  }

  void acceptClientConnection() throws IOException {
    Log.d("L2tpTetherActivity", "acceptClientConnect");
    while (true) {
      BluetoothSocket socket = null;
      try {
        Log.d("L2tpTetherActivity", "accept");
        socket = mServer.accept();
      } catch (IOException e) {
        Log.d("L2tpTetherActivity", "IOException: " + e.getMessage());
        break;
      }

      if (socket != null) {
        handleClientConnection(socket);
      }
    }
  }

  void handleClientConnection(BluetoothSocket socket) throws IOException {
    Log.d("L2tpTetherActivity", "handleClientConnection");
    mInStream = socket.getInputStream();
    mOutStream = socket.getOutputStream();
  }

  void handleCommandStream() {
    Log.d("L2tpTetherActivity", "handleCommandStream");
    BufferedReader bufferedStream = new BufferedReader(new InputStreamReader(mInStream));

    while (true) {
      String response = "ERROR\r\n";
      String line = bufferedStream.readLine();
      Log.d("L2tpTetherActivity", line);

      if ("AT".equals(line) ||
          "AT&FE0Q0V1".equals(line) ||
          "AT+CGDCONT=1,\"IP\",\"internet\"".equals(line) ||
          "AT+CGDCONT=1,\"IP\",\"apn\"".equals(line)) {
        response = "OK\r\n";
      } else if ("CLIENT".equals(line)) {
        response = "CLIENTSERVER\r\n";
      } else if ("ATD*99***1#".equals(line)) {
        response = "CONNECT\r\n";
      }

      Log.d("L2tpTetherActivity", " -> " + response);
      mOutStream.write(response.getBytes());

      if ("CLIENTSERVER\r\n".equals(response)) {
        mL2tpClient.startSession();
        handleDataStream();
      }
    }
  }

  void handleDataStream() {
    Log.d("L2tpTetherActivity", "handleDataStream");

    final HdlcFramer hdlc = new HdlcFramer();
    byte[] buf = new byte[1500];
    while (true) {
      int len = mInStream.read(buf);
      Log.d("L2tpTetherActivity", "read=" + len);
      hdlc.put(buf);

      while (true) {
        try {
          mL2tpClient.sendPacket(new L2tpPacket() {
            @Override
            void getPayload(ByteBuffer dest) {
              hdlc.getFrame(dest);
            }
          });
        } catch (BufferUnderflowException e) {
          Log.d("L2tpTetherActivity", "BufferUnderflowException: " + e.getMessage());
          break;
        }
      }
    }
  }
}
