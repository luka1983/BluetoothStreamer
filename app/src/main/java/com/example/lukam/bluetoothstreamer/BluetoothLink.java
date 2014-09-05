package com.example.lukam.bluetoothstreamer;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.util.Log;

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
    private ConnectThread mConnectThread;
    private CommunicationThread mCommunicationThread;
    private State mState;

    // Link states
    private static final enum State { NONE, LISTENING, CONNECTING, CONNECTED };

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
}
