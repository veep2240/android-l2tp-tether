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
import java.util.UUID;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;

public class L2tpTetherActivity extends Activity implements Runnable
{
    private static final String NAME = "L2TP Tether";
    private static final UUID UUID_SERIAL_PORT_PROFILE = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID UUID_DUN_PROFILE = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB");
    private static final String L2TP_HOST = "lns.theusualco.com";
    private static final int L2TP_PORT = 1701;
    private static final int L2TP_RECEIVE_TIMEOUT_MS = 3000;

    private InetAddress mL2tpAddr;
    private DatagramSocket mL2tpSocket;
    private BluetoothAdapter mAdapter;
    private BluetoothServerSocket mServer;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d("L2tpTetherActivity", "onKeyDown");
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        try {
            mServer = mAdapter.listenUsingRfcommWithServiceRecord(NAME, UUID_DUN_PROFILE);
        } catch (IOException e) {
            Log.d("L2tpTetherActivity", "IOException in listen");
        }

        Thread thread = new Thread(this);
        thread.start();
        return super.onKeyDown(keyCode, event);
    }

    boolean makeTunnel() {
        Log.d("L2tpTetherActivity", "makeTunnel");

        try {
          mL2tpAddr = InetAddress.getByName(L2TP_HOST);
        } catch (UnknownHostException e) {
          Log.d("L2tpTetherActivity", "resolve failed");
          return false;
        }

        try {
          mL2tpSocket = new DatagramSocket();
          mL2tpSocket.setSoTimeout(L2TP_RECEIVE_TIMEOUT_MS);
        } catch (SocketException e) {
          Log.d("L2tpTetherActivity", "socket creation failed");
          return false;
        }

        L2tpControlPacket sccrq = new L2tpControlPacket(L2tpControlPacket.L2TP_CTRL_TYPE_SCCRQ);
        sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_PROTOCOL_VERSION, L2tpControlPacket.L2TP_PROTOCOL_V1_0));
        sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_HOST_NAME, "hostname"));
        sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_FRAMING_CAPABILITIES, (int)0));
        sccrq.addAvp(new L2tpAvp(true, L2tpAvp.L2TP_AVP_ASSIGNED_TUNNEL_ID, (short)1));

        byte[] packet = new byte[1500];
        ByteBuffer buf = ByteBuffer.wrap(packet);
        int length = sccrq.get(buf);

        try {
          mL2tpSocket.send(new DatagramPacket(packet, length, mL2tpAddr, L2TP_PORT));
        } catch (IOException e) {
          Log.d("L2tpTetherActivity", "packet send failed");
          return false;
        }

        DatagramPacket received = new DatagramPacket(packet, packet.length);
        try {
          mL2tpSocket.receive(received);
          Log.d("L2tpTetherActivity", "got response");
        } catch (IOException e) {
          Log.d("L2tpTetherActivity", "packet receive failed");
          return false;
        }

        return true;
    }

    public void run() {
        Log.d("L2tpTetherActivity", "run");
        makeTunnel();
        makeDiscoverable();
        makeListenSocket();
    }

    void makeDiscoverable() {
        Log.d("L2tpTetherActivity", "makeDiscoverable");
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        startActivity(discoverableIntent);
    }

    void makeListenSocket() {
        while (true) {
        try {
            Log.d("L2tpTetherActivity", "makeListenSocket");

            Log.d("L2tpTetherActivity", "accept");
            BluetoothSocket socket = mServer.accept();

            Log.d("L2tpTetherActivity", "read");
            InputStream inStream = socket.getInputStream();
            OutputStream outStream = socket.getOutputStream();
            BufferedReader bufferedStream = new BufferedReader(new InputStreamReader(inStream));

            while (true) {
              String line = bufferedStream.readLine();
              Log.d("L2tpTetherActivity", line);
              String response = "ERROR\r\n";
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
              outStream.write(response.getBytes());
              if ("CLIENTSERVER\r\n".equals(response)) {
                //outStream.write(CONFREQ);
                while (true) {
                  int c = inStream.read();
                  Log.d("L2tpTetherActivity", "PPP read " + c);
                }
              }
            }
        } catch (IOException e) {
            Log.d("L2tpTetherActivity", "IOException");
            break;
        } catch (NullPointerException e) {
            Log.d("L2tpTetherActivity", "NullPointerException");
            break;
        }
        }
    }
}

/*
    private static final byte[] CONFREQ = {
        (byte)0xC0, (byte)0x21,  // PPP LCP
        (byte)0x01,  // Configure-Request
        (byte)0x01,  // Identifier
        (byte)0x00, (byte)0x04  // Length
        };

        byte[] sccrqCheck = {
          (byte)0xc8, (byte)0x02,  // TLS+VER
          (byte)0x00, (byte)0x3c,  // length = 12+8+8+14+10+8 = 60
          (byte)0x00, (byte)0x00,  // tunnel id
          (byte)0x00, (byte)0x00,  // session id
          (byte)0x00, (byte)0x00,  // Ns
          (byte)0x00, (byte)0x00,  // Nr

          // message type
          (byte)0x80, (byte)0x08,  // mandatory, length=6+2
          (byte)0x00, (byte)0x00,  // ietf vendor id
          (byte)0x00, (byte)0x00,  // type
          (byte)0x00, (byte)0x01,  // sccrq

          // protocol version
          (byte)0x80, (byte)0x08,  // mandatory, length=6+2
          (byte)0x00, (byte)0x00,  // ietf vendor id
          (byte)0x00, (byte)0x02,  // type
          (byte)0x01, (byte)0x00,  // 1.0

          // host name
          (byte)0x80, (byte)0x0e,  // mandatory, length=6+8
          (byte)0x00, (byte)0x00,  // ietf vendor id
          (byte)0x00, (byte)0x07,  // type
          (byte)0x68, (byte)0x6f, (byte)0x73, (byte)0x74, (byte)0x6e, (byte)0x61, (byte)0x6d, (byte)0x65,  // hostname

          // framing capabilities
          (byte)0x80, (byte)0x0a,  // mandatory, length=6+4
          (byte)0x00, (byte)0x00,  // ietf vendor id
          (byte)0x00, (byte)0x03,  // type
          (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,  // 0

          // assigned tunnel id
          (byte)0x80, (byte)0x08,  // mandatory, length=6+2
          (byte)0x00, (byte)0x00,  // ietf vendor id
          (byte)0x00, (byte)0x09,  // type
          (byte)0x00, (byte)0x01,  // 1
        };

        assert ByteBuffer.wrap(sccrqCheck).equals(ByteBuffer.wrap(packet, 0, length));
        Log.d("L2tpTetherActivity", "Buffers match");
*/
