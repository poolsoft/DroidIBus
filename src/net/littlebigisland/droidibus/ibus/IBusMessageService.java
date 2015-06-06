package net.littlebigisland.droidibus.ibus;
/**
 * IBusService
 * Communicate with the IBus using the IOIO
 * All Read/Writes are done here
 * 
 * @author Ted S <tass2001@gmail.com>
 * @package net.littlebigisland.droidibus.ibus.IBusMessageService
 * 
 */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import net.littlebigisland.droidibus.ibus.systems.BoardMonitorSystemCommand;
import net.littlebigisland.droidibus.ibus.systems.BroadcastSystemCommand;
import net.littlebigisland.droidibus.ibus.systems.GlobalBroadcastSystemCommand;
import net.littlebigisland.droidibus.ibus.systems.GFXNavigationSystemCommand;
import net.littlebigisland.droidibus.ibus.systems.IKESystemCommand;
import net.littlebigisland.droidibus.ibus.systems.RadioSystemCommand;
import net.littlebigisland.droidibus.ibus.systems.SteeringWheelSystemCommand;
import net.littlebigisland.droidibus.ibus.systems.TelephoneSystemCommand;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.Uart;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOService;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;


public class IBusMessageService extends IOIOService{
    
    private String TAG = "DroidIBus";
    private final IBinder mBinder = new IOIOBinder();
    private ArrayList<IBusCommand> mCommandQueue = new ArrayList<IBusCommand>();
    @SuppressLint("UseSparseArrays")
    private Map<Byte, IBusSystemCommand> IBusSysMap = new HashMap<Byte, IBusSystemCommand>();
    @SuppressLint("UseSparseArrays")
    private Map<Byte, String> mDeviceLookup = new HashMap<Byte, String>();
    private boolean mIsIOIOConnected = false;
    private int mClientsConnected = 0;
    
    
    public class IOIOBinder extends Binder {
        public IBusMessageService getService() {
            return IBusMessageService.this;
        }
    }
    
    /**
     * This is the thread on which all the IOIO activity happens. It will be run
     * every time the application is resumed and aborted when it is paused. The
     * method setup() will be called right after a connection with the IOIO has
     * been established (which might happen several times!). Then, loop() will
     * be called repetitively until the IOIO gets disconnected.
     * @see ioio.lib.util.android.IOIOService#createIOIOLooper()
     */
    @Override
    protected IOIOLooper createIOIOLooper() {
        return new BaseIOIOLooper() {
            
            private Uart IBusConn;
            private InputStream busIn;
            private OutputStream busOut;
            
            private DigitalOutput statusLED;
            private DigitalOutput faultPin;
            private DigitalOutput chipSelectPin;
            
            private int IBusRXPinId = 10;
            private int IBusTXPinId = 13;
            private int chipSelectPinId = 11;
            private int faultPinId = 12;
            
            private int msgLength = 0;
            
            private Calendar time;
            private long lastRead;
            private long lastSend;

            private ArrayList<Byte> readBuffer = new ArrayList<Byte>();
            
            /**
             * Called every time a connection with IOIO has been established.
             * Setup the connection to the IBus and bring up the CS/Fault Pins on the MCP2004
             * 
             * @throws ConnectionLostException
             *             When IOIO connection is lost.
             * 
             * @see ioio.lib.util.AbstractIOIOActivity.IOIOThread#setup()
             */
            @Override
            protected void setup() throws ConnectionLostException, InterruptedException {
                Log.d(TAG, "Running IOIO Setup");
                IBusConn = ioio_.openUart(
                    IBusRXPinId, IBusTXPinId, 9600, Uart.Parity.EVEN, Uart.StopBits.ONE
                );
                statusLED = ioio_.openDigitalOutput(IOIO.LED_PIN, true);
                /* Set these HIGH per the MCP2004 data sheet. 
                 * Not required so long as you put a 270ohm resistor from 5V to pin 2 */
                chipSelectPin = ioio_.openDigitalOutput(chipSelectPinId, true);
                chipSelectPin.write(true);
                faultPin = ioio_.openDigitalOutput(faultPinId, true);
                faultPin.write(true);
                
                busIn = IBusConn.getInputStream();
                busOut = IBusConn.getOutputStream();
                
                // Timeout stuff 
                time = Calendar.getInstance();
                lastRead = time.getTimeInMillis();
                // Add 250ms so that we don't spam the buffer
                // when the view starts asking IKE for data 
                lastSend = time.getTimeInMillis() + 250;
                mIsIOIOConnected = true;
            }
            
            /**
             * Called repetitively while the IOIO is connected.
             * Reads and writes to the IBus
             * @throws ConnectionLostException
             *             When IOIO connection is lost.
             * 
             * @see ioio.lib.util.AbstractIOIOActivity.IOIOThread#loop()
             */
            @Override
            public void loop() throws ConnectionLostException, InterruptedException {
                /*
                 * This is the main logic loop where we communicate with the IBus
                 */
                // Timeout the buffer if we don't get data for 50ms 
                if (((Calendar.getInstance().getTimeInMillis() - lastRead) > 75) && readBuffer.size() > 2) {
                        String data = "";
                        for(int i = 0; i<readBuffer.size(); i++)
                            data = String.format("%s%02X ", data, readBuffer.get(i));
                        Log.d(TAG, String.format("Clearing buffer of < %s > due to timeout", data));
                        readBuffer.clear();
                }
                try {
                    /* Handle incoming IBus data.
                     * Read incoming bytes into readBuffer.
                     * Skip if there's nothing to read.
                     */
                    if (busIn.available() > 0) {
                        statusLED.write(true);
                        lastRead = Calendar.getInstance().getTimeInMillis();
                        readBuffer.add((byte) busIn.read());
                        /* Set message size to a large number (256) if we haven't gotten the message
                         * length from the second byte of the IBus Message, else set message length to 
                         * the length provided by IBus.
                         */
                        if (readBuffer.size() == 1){
                            msgLength = 256;
                        }else if (readBuffer.size() == 2){
                            msgLength = (int) readBuffer.get(1);
                        }
                        if(msgLength == 0){
                            Log.d(TAG, "IBusMessageService: Got Buffer size of 0?!");
                        }
                        // Read until readBuffer contains msgLength plus two more bytes for the full message
                        if (readBuffer.size() == msgLength + 2) {
                            // Make sure the message checksum checks out and that it's at least 3 bytes in length
                            // otherwise it's invalid and should be discarded. 0x00 0x00 will pass a XOR
                            // and the prior test BUT is NOT valid and shouldn't be processed.
                            if(checksumMessage(readBuffer) && readBuffer.size() >= 3) {
                                String data = "";
                                for(int i = 0; i<readBuffer.size(); i++)
                                    data = String.format("%s%02X ", data, readBuffer.get(i));
                                Log.d(TAG, String.format(
                                    "Received Message (%s -> %s): %s",
                                    mDeviceLookup.get(readBuffer.get(0)),
                                    mDeviceLookup.get(readBuffer.get(2)),
                                    data
                                ));
                                handleMessage(readBuffer);
                            }else{
                                String data = "";
                                for(int i = 0; i<readBuffer.size(); i++)
                                    data = String.format("%s%02X ", data, readBuffer.get(i));
                                Log.d(TAG, String.format("Checksum failure or buffer too small < %s >", data));
                            }
                            readBuffer.clear();
                        }
                        statusLED.write(false);
                    }else if(mCommandQueue.size() > 0){
                        statusLED.write(true);
                        // Wait at least 100ms between messages and then write out to the bus to avoid collisions
                        if ((Calendar.getInstance().getTimeInMillis() - lastSend) > 100){
                            // Pop out the command from the Array
                            IBusCommand command = mCommandQueue.get(0);
                            // Get the command type enum
                            IBusCommandsEnum cmdType = command.commandType;
                            // Get the instance of the class which implements the method we're looking for
                            IBusSystemCommand clsInstance = IBusSysMap.get(cmdType.getSystem().toByte());
                            // Get the command Varargs to pass. Very possible that this is null and that's okay
                            Object cmdArgs = command.commandArgs;
                            byte[] outboundMsg = new byte[] {};
                            try{
                                if(cmdArgs == null){
                                    Method requestedMethod = clsInstance.getClass().getMethod(cmdType.getMethodName());
                                    outboundMsg = (byte[]) requestedMethod.invoke(clsInstance);
                                }else{
                                    Method requestedMethod = clsInstance.getClass().getMethod(cmdType.getMethodName(), Object[].class);
                                    outboundMsg = (byte[]) requestedMethod.invoke(clsInstance, cmdArgs);
                                }
                            }catch(IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException e){
                                Log.d(TAG, "Error invoking method in IBus outbound queue: " + e.toString() + " " + e.getMessage());
                                e.printStackTrace();
                            }
                            // Write the message out to the bus byte by byte
                            String out = String.format("Sending %s Command out: ", cmdType.toString());
                            for(int i = 0; i < outboundMsg.length; i++){
                                out = String.format("%s %02X", out, outboundMsg[i]);
                                busOut.write(outboundMsg[i]);
                            }
                            Log.d(TAG, out);
                            mCommandQueue.remove(0);
                            lastSend = Calendar.getInstance().getTimeInMillis();
                        }
                        statusLED.write(false);
                    }
                }catch (IOException e){
                    Log.e(TAG, String.format("IOIO IOException [%s] in IBusService.loop()", e.getMessage()));
                }
                Thread.sleep(2);
            }
            
                
            /**
             * Verify that the IBus Message is legitimate 
             * by XORing all bytes if correct, the product 
             * should be 0x00
             * 
             * @param ArrayList<byte> msgBuffer    The buffer containing all bytes in the Message
             * @return boolean     true if the message isn't corrupt, otherwise false
             */
            private boolean checksumMessage(ArrayList<Byte> msgBuffer) {
                byte cksum = 0x00;
                for(byte msg : msgBuffer){
                    cksum = (byte) (cksum ^ msg);
                }
                return (cksum == 0x00) ? true : false;
            }
            
            @Override
            public void disconnected(){
                mIsIOIOConnected = false;
            }
                
            /**
             * Send the inbound message to the correct Handler 
             * @param msg
             */
            private void handleMessage(ArrayList<Byte> msg){
                // The third byte of the message indicates it's destination
                try{
                    IBusSysMap.get(msg.get(2)).mapReceived(msg);
                }catch(NullPointerException e){
                    // Things not in the map throw a NullPointerException
                }
            }
        };
    }
    
    public boolean getLinkState(){
        return mIsIOIOConnected;
    }
    
    public void registerCallback(IBusCallbackReceiver cb, Handler handler){
        if(IBusSysMap.size() > 0){
            for (IBusSystemCommand sys : IBusSysMap.values()){
                sys.registerCallback(cb, handler);
            }
        }
    }
    
    /**
     * Add an action into the queue of message waiting to be sent
     * @param cmd IBusCommand instance to be performed
     */
    public void sendCommand(IBusCommand cmd){
        mCommandQueue.add(cmd);
    }
    
    public void unregisterCallback(IBusCallbackReceiver cb){
        if(IBusSysMap.size() > 0){
            for (IBusSystemCommand sys : IBusSysMap.values()){
                sys.unregisterCallback(cb);
            }
        }
    }
    
    @Override
    public IBinder onBind(Intent intent){
        mClientsConnected++;
        return mBinder;
    }
    
    @Override
    public void onDestroy(){
        super.onDestroy();
        Log.d(TAG, "IBusMessageService: onDestroy()");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId){
        // Call super.onStart because it starts the IOIOAndroidApplicationHelper 
        // and super.onStartCommand is not implemented
        super.onStart(intent, startId);
        Log.d(TAG, "IBusMessageService: onStartCommand()");
        for(DeviceAddressEnum d : DeviceAddressEnum.values()){
            mDeviceLookup.put(d.toByte(), d.name());
        }
        // Initiate values for IBus System handlers
        if(IBusSysMap.size() == 0){
            Log.d(TAG, "IBusMessageService: Filling IBusSysMap");
            IBusSysMap.put(
                DeviceAddressEnum.BoardMonitor.toByte(),
                new BoardMonitorSystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.Broadcast.toByte(),
                new BroadcastSystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.GFXNavigationDriver.toByte(),
                new GFXNavigationSystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.GlobalBroadcast.toByte(),
                new GlobalBroadcastSystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.InstrumentClusterElectronics.toByte(),
                new IKESystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.Radio.toByte(),
                new RadioSystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.MultiFunctionSteeringWheel.toByte(),
                new SteeringWheelSystemCommand()
            );
            IBusSysMap.put(
                DeviceAddressEnum.Telephone.toByte(),
                new TelephoneSystemCommand()
            );
        }
        return START_STICKY;
    }
    
    @Override
    public boolean onUnbind(Intent intent){
        super.onUnbind(intent);
        Log.d(TAG, "IBusMessageService: onUnbind()");
        mClientsConnected--;
        if(mClientsConnected == 0){
            stopSelf();
        }
        return false;
    }

}