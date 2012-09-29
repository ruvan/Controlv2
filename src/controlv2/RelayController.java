
package controlv2;

import java.io.IOException;
import gnu.io.RXTXCommDriver;
import java.io.*;
import gnu.io.*;

/**
 *
 * @author Tyrone
 */
public class RelayController extends Thread {
    
    /**
     *  Class Variables
     */
    Controlv2 ctrl;
    SerialPort relaySerialPort;
    InputStream relayInputStream;
    OutputStream relayOutputStream;
    
    // Triangle / Relay
    static Relay[][] relayTable = new Relay[19][8];
    static Flower[][] flowers = new Flower[3][12];
    
    public RelayController(Controlv2 ctrl, String port, int baud, String programName) {
        this.ctrl = ctrl;
        try {
            relaySerialPort = initializeSerial(port, baud, programName);
            relayInputStream = relaySerialPort.getInputStream();
            relayOutputStream = relaySerialPort.getOutputStream();
            initializeFlowers();
        } catch (IOException ex) {
            System.out.println("Could not initialize relay components");
        }
        
    }
    
    public void run() {
        turnOff();
        turnOn();
        while(true) {
            runSequence();
        }
    }
    
    /**
     * Open a serial connection note: assumes databits=8, stopbits=1, no parity
     * and no flow control.
     */
    public SerialPort initializeSerial(String port, int baud, String programName) throws IOException {
        SerialPort serialPort;
        try {
            
            CommPortIdentifier portId = CommPortIdentifier.getPortIdentifier(port);
            serialPort = (SerialPort) portId.open(programName, 5000);
            serialPort.setSerialPortParams(baud, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
            serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
        } catch (NoSuchPortException ex) {
            throw new IOException("No such port");
        } catch (PortInUseException ex) {
            throw new IOException("Port in use");
        } catch (UnsupportedCommOperationException ex) {
            throw new IOException("Unsupported serial port parametes");
        }
        return serialPort;
    }
    
    /**
     * This method should create all triangles big and small
     * and populate the triangle / relay related tables.
     */
    public static void initializeFlowers() {
        for(int level = 0; level < 3; level++){
            for(int flowerNumber = 0; flowerNumber < 12; flowerNumber++){
                flowers[level][flowerNumber] = new Flower(level, flowerNumber, relayTable);
            }
        }
        
        // initialise the rest of the relayTable
        // PSU's and unsed 7th relay
        for(int i=0; i<19; i++){
            relayTable[i][0] = new Relay();
            relayTable[i][7] = new Relay();
        }
        // bank 1
        for(int i=1; i<7; i++){
            relayTable[0][i] = new Relay();
        }
    }
    
    public void send(int i) {
        try {
            relayOutputStream.write(i);
        } catch (IOException ex) {
            ex.getMessage();
        }
    }
    
    public void sleep(int time) {
        try {
            Thread.currentThread().sleep(time);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    public void runSequence() {
        System.out.println("Starting chase bloom");
        for(int level = 0; level < 3; level++){
            for(int flowerNumber = 0; flowerNumber < 12; flowerNumber++){
                flowers[level][flowerNumber].allOn();
                updateRelays();
                sleep(2000);
                flowers[level][flowerNumber].allOff();
                updateRelays();
            }
        }
    }
    
    public void turnOff() {
        //pull in all actuators
        for (int bank = 1; bank < 19; bank++) {
            for (int relay = 1; relay < 8; relay++) {
                relayTable[bank][relay].setState(false);
            }
        }
        updateRelays();

        // TODO: put in wait command, be weary of this pausing other aspects of totem.

        // turn off PSU's
        for (int bank = 0; bank < 19; bank++) {
            relayTable[bank][0].setState(false);
        }
        // relay coil 12V supply
        relayTable[0][1].setState(false);
        updateRelays();
    }

    public void turnOn() {
        // turn on all PSU's
        for (int bank = 0; bank < 19; bank++) {
            relayTable[bank][0].setState(true);
        }
        // relay coil 12V supply
        relayTable[0][1].setState(true);
        updateRelays();
    }

    public void updateRelays() {
        for (int bank = 0; bank < 19; bank++) {
            int command = 0;
            for (int relay = 0; relay < 8; relay++) {
                if (relayTable[bank][relay].getState()) {
                    command += Math.pow(2, relay);
                }
            }
            sleep(20);
            send(254);
            sleep(20);
            send(140);
            sleep(20);
            send(command);
            sleep(20);
            send(bank + 1); // +1 because ProXR starts at 1
            sleep(20);
        }
    }
}
