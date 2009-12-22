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

        mL2tpClient.startTunnel();
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
