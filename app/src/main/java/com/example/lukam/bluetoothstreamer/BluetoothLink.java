package com.example.lukam.bluetoothstreamer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import android.os.Handler;

/**
 * This class manages Bluetooth connection between two devices
 * Three threads of execution:
 *      1. Accept thread        - socket server thread, runs until communication is established
 *      2. Connecting thread    - client thread, asks for connection to a known device
 *      3. Communication thread - generic communication thread, same for all endpoints
 *
 * Bluetooth link can posses one of the following states:
 *      1. NONE
 *      2. LISTENING
 *      3. CONNECTING
 *      4. CONNECTED
 *
 * Bluetooth link can be set in one of the following modes of operation. The link mode defines the
 * link behaviour on connection loss
 *      1. UNDEFINED        - mode not defined
 *      2. SOCKET_SERVER    - upon connection loss the link is put in LISTENING state
 *      3. CLIENT           - upon connection loss the link is put in CONNECTING state
 */
public class BluetoothLink {

    // Debugging
    private static final String TAG = "BluetoothLink";
    private static final boolean DEBUG = true;

    // Name for the SDP record when creating server socket
    private static final String BL_SDP_SERVICE_NAME = "BluetoothLink";

    // Unique UUID for this application
    private static final UUID BL_UUID = UUID.fromString("016b3cd0-38c7-11e4-916c-0800200c9a66");

    // Members
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mAcceptThread;
    private ConnectingThread mConnectingThread = null;
    private CommunicationThread mCommunicationThread;
    private BLState mState;
    private BluetoothDevice mLastConnectedDevice = null;
    private BLMode mMode;

    // Link modes
    public static enum BLMode { UNDEFINED, SOCKET_SERVER, CLIENT }

    // Link states
    public static enum BLState { NONE, LISTENING, CONNECTING, CONNECTED }

    // Link messages
    public static enum BLMessage { STATE_CHANGED, MODE_CHANGED, READ, WRITE }

    /**
     * Constructor. Prepares new BluetoothLink
     * @param handler   A Handler to send message back to UI Activity
     */
    public BluetoothLink(Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = BLState.NONE;
        mMode = BLMode.UNDEFINED;
        mHandler = handler;
    }

    /**
     * Set the new link mode
     * @param mode     Link new mode of operation
     */
    private synchronized void setMode(BLMode mode) {
        if (DEBUG) Log.d(TAG, "setMode() " + mMode + " -> " + mode);
        mMode = mode;

        // Inform service user about BluetoothLink state change
        mHandler.obtainMessage(BLMessage.MODE_CHANGED.ordinal(), mode.ordinal(), -1).sendToTarget();
    }

    /**
     * Set the current state
     * @param state     Link new state
     */
    private synchronized void setState(BLState state) {
        if (DEBUG) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;

        // Inform service user about BluetoothLink state change
        mHandler.obtainMessage(BLMessage.STATE_CHANGED.ordinal(), state.ordinal(), -1).sendToTarget();
    }

    /**
     * Return the current connect state
     */
    public synchronized BLState getState() {
        return mState;
    }

    /**
     * Start the socket server
     */
    public synchronized void accept() {
        if (DEBUG) Log.d(TAG, "Starting link...");

        if (mMode != BLMode.SOCKET_SERVER)
            setMode(BLMode.SOCKET_SERVER);

        // Cancel any thread attempting to make a connection
        if (mConnectingThread != null) {
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        // Cancel communication thread if already running
        if (mCommunicationThread != null) {
            mCommunicationThread.cancel();
            mCommunicationThread = null;
        }

        // Start the socket server thread
        if (mAcceptThread == null) {
            mLastConnectedDevice = null;
            setState(BLState.LISTENING);
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
        } else {
            if (DEBUG) Log.d(TAG, "AcceptThread already running...");
        }
    }

    /**
     * Start the connection thread tp initiate connection to a remote device
     * @param   device  The BluetoothDevice to connect
     */
    public synchronized void connect(BluetoothDevice device) {

        if (mMode != BLMode.CLIENT)
            setMode(BLMode.CLIENT);

        if (device == null) {
            return;
        }

        if (DEBUG) Log.d(TAG, "connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == BLState.CONNECTING) {
            if (mConnectingThread != null) {
                mConnectingThread.cancel();
                mConnectingThread = null;
            }
        }
        // Cancel any thread currently running a connection
        if (mCommunicationThread != null) {
            mCommunicationThread.cancel();
            mCommunicationThread = null;
        }

        // Cancel any thread accepting connection
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        mConnectingThread = new ConnectingThread(device);
        mConnectingThread.start();
        setState(BLState.CONNECTING);
    }

    /**
     * Start the CommunicationThread
     * @param   socket  BluetoothSocket on which connection was made
     * @param   device  BluetoothDevice that has been connected
     */
    private synchronized void communicate(BluetoothSocket socket, BluetoothDevice device) {
        if (DEBUG) Log.d(TAG, "Starting communication with connected device: " + device.getName());

        // Cancel the thread that completed the connection
        if (mConnectingThread != null) {
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        // Cancel the accept thread, we support one-to-one communication only
        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        // Start the communication thread
        mCommunicationThread = new CommunicationThread(socket);
        mCommunicationThread.start();

        setState(BLState.CONNECTED);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (DEBUG) Log.d(TAG, "STOP");

        // Link stopping sets the mode of the link to undefined
        if (mMode != BLMode.UNDEFINED)
            setMode(BLMode.UNDEFINED);

        if (mAcceptThread != null) {
            mAcceptThread.cancel();
            mAcceptThread = null;
        }

        if (mConnectingThread != null) {
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        if (mCommunicationThread != null) {
            mCommunicationThread.cancel();
            mCommunicationThread = null;
        }

        setState(BLState.NONE);
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param   out Bytes to write
     */
    public void write(byte[] out) {
        // Create temporary object
        CommunicationThread r;
        synchronized (this) {
            if (mState != BLState.CONNECTED)
                return;
            r = mCommunicationThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Perform operations on connection fail
     */
    private void postMessage(BLMessage message, final String text ) {
        Message msg = mHandler.obtainMessage(message.ordinal());
        mHandler.sendMessage(msg);
    }

    /**
     * Socket server thread - waiting for incoming connection
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;

        // Constructor
        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            // Create a new listening server socket
            try {
                // this call registers the service NAME/UUID
                // pair with SDP server with RFCOMM protocol
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

            BluetoothSocket mmSocket = null;

            // while not connected, listen to a server socket
            while (mState != BLState.CONNECTED) {
                try {
                    // blocking call
                    mmSocket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "accept() failed");
                    break;
                }

                // connection accepted...
                if (mmSocket != null) {
                    synchronized (this) {
                        switch (mState) {
                            case LISTENING:
                            case CONNECTING:
                                // switch state to connected
                                communicate(mmSocket, mmSocket.getRemoteDevice());
                                break;
                            case NONE:
                            case CONNECTED:
                                // we do not support multiple connections so if connection is
                                // already running, terminate new one
                                try {
                                    mmSocket.close();
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

    /**
     * Connecting client thread - runs while connection is being established
     */
    private class ConnectingThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectingThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            try {
                tmp = device.createRfcommSocketToServiceRecord(BL_UUID);
            } catch(IOException e) {
                Log.e(TAG, "Could not create BluetoothSocket");
            }
            mmSocket = tmp;
        }

        public void run() {
            if (DEBUG) Log.d(TAG, "BEGIN mConnectingThread");
            setName("Connecting thread");

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            while (true) {
                try {
                    // blocking call - returns only on a successful connection or an exception
                    if (!mmSocket.isConnected()) {
                        mmSocket.connect();
                        break;
                    }
                } catch (IOException e) {

                    Log.e(TAG, "Could not connect(), trying again in 2 s...", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e2) {
                        Log.e(TAG, "mConnectiongThread could not sleep", e);
                    }
                    Log.e(TAG, "Trying again...", e);
                }
            }

            // Release ConnectionThread because we're done
            synchronized (BluetoothLink.this) {
                mConnectingThread = null;
                mLastConnectedDevice = mmDevice;
            }

            if (DEBUG) Log.d(TAG, "END mConnectingThread");

            // start the communication thread
            communicate(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of client socket failed");
            }
        }
    }

    /**
     * Communication thread - after successful connection is established this
     * thread manages connection. The idea is to read the input stream as fast
     * as possible in order not to limit the receiving data buffer.
     */
    private class CommunicationThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        // Constructor
        private CommunicationThread(BluetoothSocket socket) {
            Log.d(TAG, "create CommunicationThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Could not retrieve input and output streams from socket", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            if (DEBUG) Log.d(TAG, "BEGIN mCommunicationThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // Prioritize input stream reading
            while (true) {
                try {
                    // Read from the InputStream - blocking call
                    bytes = mmInStream.read(buffer);

                    // Pass the obtained data to a service user via provided handler
                    mHandler.obtainMessage(BLMessage.READ.ordinal(), bytes, -1, buffer).sendToTarget();

                } catch (IOException e) {
                    Log.e(TAG, "connection lost", e);

                    // Restart the service
                    // must be synchronized block since mIsServerSocket is set in main thread
                    synchronized (BluetoothLink.this) {
                        if (mMode == BLMode.SOCKET_SERVER)
                            accept();
                        else if (mLastConnectedDevice != null && mMode == BLMode.CLIENT)
                            connect(mLastConnectedDevice);
                        break;
                    }
                }
            }
            if (DEBUG) Log.d(TAG, "END mCommunicationThread");
        }

        /**
        * Write to the connected OutputStream
        * @param buffer    Bytes to write
        */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close() connected socket", e);
            }
        }
    }
}
