
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
    static Boolean[][] relayTable = new Boolean[19][8];
    static Flower[][] flower = new Flower[3][12];
    
    public RelayController(Controlv2 ctrl, String port, int baud, String programName) {
        this.ctrl = ctrl;
        try {
            relaySerialPort = initializeSerial(port, baud, programName);
            relayInputStream = relaySerialPort.getInputStream();
            relayOutputStream = relaySerialPort.getOutputStream();
        } catch (IOException ex) {
            System.out.println("Could not initialize relay components");
        }
        
    }
    
    public void run() {
        
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
    public static void initialiseFlowers() {
        for(int level = 0; level < 3; level++){
            for(int flowerNumber = 0; flowerNumber < 12; flowerNumber++){
                flower[level][flowerNumber] = new Flower(level, flowerNumber, relayTable);
            }
        }
        
        // initialise the rest of the relayTable
        // PSU's and unsed 7th relay
        for(int i=0; i<19; i++){
            relayTable[i][0] = new Boolean(false);
            relayTable[i][7] = new Boolean(false);
        }
        // bank 1
        for(int i=1; i<7; i++){
            relayTable[0][i] = new Boolean(false);
        }
    }
}
