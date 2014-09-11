package com.example.lukam.bluetoothstreamer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;

import android.os.Handler;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Set;


public class MainActivity extends Activity {

    private BluetoothLink mLink = null;
    private BluetoothLink.BLMessage message;
    private boolean isServer = false;
    private ArrayAdapter<String> mPairedDevicesArrayAdapter;
    private BluetoothAdapter mBtAdapter;

    // Debug
    private final String TAG = "BluetoothStreamer";
    private final boolean DEBUG = true;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (BluetoothLink.BLMessage.values()[msg.what]) {
                case STATE_CHANGED:
                    TextView textLinkStatus = (TextView)findViewById(R.id.textLinkStatus);
                    switch (BluetoothLink.BLState.values()[msg.arg1]) {
                        case LISTENING:
                            if (DEBUG) Log.d(TAG, "Link reports state change to LISTENING");
                            textLinkStatus.setText("Link status: listening...");
                            break;
                        case CONNECTING:
                            if (DEBUG) Log.d(TAG, "Link reports state change to CONNECTING");
                            textLinkStatus.setText("Link status: connecting...");
                            break;
                        case CONNECTED:
                            if (DEBUG) Log.d(TAG, "Link reports state change to CONNECTED");
                            textLinkStatus.setText("Link status: connected");
                            mLink.write(String.format("%d", System.currentTimeMillis()).getBytes());

                            //sendSomeKB(2000, mLink, true);
                            //sendSomeMB(2, mLink);
                            break;
                        case NONE:
                            if (DEBUG) Log.d(TAG, "Link reports state change to NONE");
                            textLinkStatus.setText("Link status: awaiting user selection");
                            break;
                    }
                    break;
                case READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    mLink.write(String.format("%d", System.currentTimeMillis()).getBytes());
                    //if (DEBUG) Log.d(TAG, "Received message: " + readMessage);
                    TextView trafficText = (TextView) findViewById(R.id.textTraffic);
                    trafficText.setText("Traffic: " + readMessage);

                    if (readMessage.equals(String.format("<ok %d>", 2000 * 1024)))
                        sendSomeKB(2000, mLink, false);

                    break;
                case WRITE:
                    if (DEBUG) Log.d(TAG, "Data sent");

            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Configure switch button
        final Switch switchIsServer = (Switch)findViewById(R.id.switchIsServer);
        switchIsServer.setChecked(this.isServer);
        switchIsServer.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean isChecked) {
                if (isChecked) {
                    if (DEBUG) Log.d(TAG, "Selected server mode");
                    isServer = true;
                    // Set link to socket server mode and accept connection
                    mLink.accept();
                } else {
                    if (DEBUG) Log.d(TAG, "Selected client mode");
                    isServer = false;
                    // Stop all link threads
                    mLink.stop();
                }
            }
        });

        // Create new BluetoothLink instance
        mLink = new BluetoothLink(mHandler);

        // Get the local Bluetooth adapter
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBtAdapter == null) {
            Log.d(TAG, "Bluetooth not supported");
        } else {
            Log.d(TAG, "Bluetooth supported");
        }
        // TODO add two checks: if mBtAdapter exists, if mBtAdapter is enabled

        // Get a set of currently paired devices
        Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

        // Configure device listView
        final ListView pairedDevicesList = (ListView)findViewById(R.id.listPairedDevices);
        mPairedDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.device_name);
        pairedDevicesList.setAdapter(mPairedDevicesArrayAdapter);
        pairedDevicesList.setOnItemClickListener(mPairedDeviceClickListener);

        // If there are paired devices, add each one to the ArrayAdapter
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                mPairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        } else {
            String noDevices = "No paired devices found";
            mPairedDevicesArrayAdapter.add(noDevices);
        }
    }

    private OnItemClickListener mPairedDeviceClickListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View v, int i, long l) {
            if (DEBUG)
                Log.d(TAG, String.format("Clicked %s", ((TextView) v.findViewById(R.id.textView)).getText().toString()));

            if (!isServer) {
                // TODO implement better selection mechanism not based on device MAC address string
                String info = ((TextView) v).getText().toString();
                String address = info.substring(info.length() - 17);

                // Get the BluetoothDevice object
                BluetoothDevice device = mBtAdapter.getRemoteDevice(address);
                // Attempt to connect to the device
                mLink.connect(device);
            }

        }
    };

    //****************************************************************
    private void sendSomeKB(int howMuch, BluetoothLink link, boolean preamble) {
        byte[] oneKB = new byte[1024];

        if (preamble) {
            if (DEBUG) Log.d(TAG, String.format("Sending preamble: %s", String.format("<start_file %d>", 1024 * howMuch)));
            link.write(String.format("<start_file %d>", 1024 * howMuch).getBytes());
        } else {

            for (int i = 0; i < howMuch; i++) {
                if (DEBUG) Log.d(TAG, String.format("Writing %d kB", i + 1));
                //TextView trafficText = (TextView) findViewById(R.id.textTraffic);
                //trafficText.setText("Traffic: " + String.format("Writing %d kB", i + 1));
                link.write(oneKB);
            }

            if (DEBUG) Log.d(TAG, "Data sent");
        }
    }
    //****************************************************************

    //****************************************************************
    private void sendSomeMB(int howMuch, BluetoothLink link) {
        byte[] data = new byte[1024000 * howMuch];

        if (DEBUG) Log.d(TAG, String.format("Sending %d MBs", howMuch));

        link.write("<start_file>".getBytes());
        link.write(data);
        link.write("<end_file>".getBytes());

        if (DEBUG) Log.d(TAG, "Data sent");
    }
    //****************************************************************

}
