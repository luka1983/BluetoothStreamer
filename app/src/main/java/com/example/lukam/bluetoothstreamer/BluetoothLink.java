package com.example.lukam.bluetoothstreamer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.UUID;
import android.os.Handler;

/**
 * This class manages Bluetooth connection between two devices
 * Three threads of execution:
 *      1. Accept thread
 *      2. Connecting thread
 *      3. Communication thread
 *
 * Bluetooth link can posses one of the following states:
 *      1. NONE
 *      2. LISTENING
 *      3. CONNECTING
 *      4. CONNECTED
 */
public class BluetoothLink {

    // Debugging
    private static final String TAG = "BluetoothLink";
    private static final boolean DEBUG = true;

    // Name for the SDP record when creating server socket
    private static final String BL_SDP_SERVICE_NAME = "BluetoothLink";

    // Unique UUID for this application
    private static final UUID BL_UUID = UUID.fromString("c31e2950-34c2-49c0-b1f3-58d6066d7203");

    // Members
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mAcceptThread;
//    private ConnectThread mConnectThread;
 //   private CommunicationThread mCommunicationThread;
    private State mState;

    // Link states
    private static enum State { NONE, LISTENING, CONNECTING, CONNECTED };

    /**
     * Constructor. Prepares new BluetoothLink
     * @param context   The UI Activity Context
     * @param handler   A Handler to send message back to UI Activity
     */
    public BluetoothLink(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = State.NONE;
        mHandler = handler;
    }

    /**
     * Set the current state
     * @param state     Link new state
     */
    private synchronized void setState(State state) {
        if (DEBUG) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;

        // Inform the UI Activity about state change
        mHandler.obtainMessage();
    }

    /**
     * Return the current connect state
     */
    public synchronized State getState() {
        return mState;
    }

    /**
     * Start the link service
     */
    public synchronized void start() {
        if (DEBUG) Log.d(TAG, "Starting link...");

        // Cancel any thread attempting to make a connection
    }

    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;

        // Constructor
        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                tmp = mAdapter.listenUsingRfcommWithServiceRecord(BL_SDP_SERVICE_NAME, BL_UUID);
            } catch (IOException e) {
                Log.e(TAG, "listen() failed");
            }
            mmServerSocket = tmp;
        }

        // run() method
        public void run() {
            if (DEBUG) Log.d(TAG, "BEGIN mAcceptThread");
            setName("AcceptThread");

            BluetoothSocket socket = null;

            // while not connected, listen to a server socket
            while (mState != BluetoothLink.State.CONNECTED) {
                try {
                    // blocking call
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed");
                    break;
                }

                // connection accepted...
                if (socket != null) {
                    synchronized (BluetoothLink.this) {
                        switch (mState) {
                            case LISTENING:
                            case CONNECTING:
                                // switch state to connected
                                break;
                            case NONE:
                            case CONNECTED:
                                // we do not support multiple connections
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket");
                                }
                                break;
                        }
                    }
                }
            }
            if (DEBUG) Log.d(TAG, "END mAcceptThread");
        }

        public void cancel() {
            if (DEBUG) Log.d(TAG, "CANCEL mAcceptThread");
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close server socket");
            }
        }
    }
}
