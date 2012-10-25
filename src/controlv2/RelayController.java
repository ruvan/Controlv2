package controlv2;

import java.io.*;
import gnu.io.*;
import java.util.*;

/**
 *
 * @author Tyrone
 */
public class RelayController extends Thread {

    /**
     * Class Variables
     */
    static Controlv2 ctrl;
    SerialPort relaySerialPort;
    static InputStream relayInputStream;
    static OutputStream relayOutputStream;
    static int sleepTime = 10000;
    static Comparator<KineticSequence> queueComparator = new KineticSequenceComparator();
    static PriorityQueue<KineticSequence> kineticSequenceQueue = new PriorityQueue<KineticSequence>(10, queueComparator);
    
    // Triangle / Relay
    static Relay[][] relayTable = new Relay[19][8];
    static Flower[][] flowers = new Flower[3][12];
    static int[][] sensors = new int[2][10];

    public RelayController(Controlv2 ctrl, String port, int baud, String programName) {
        this.ctrl = ctrl;
        try {
            relaySerialPort = initializeSerial(port, baud, programName);
            relayInputStream = relaySerialPort.getInputStream();
            relayOutputStream = relaySerialPort.getOutputStream();
            initializeFlowers();
            
            // queue the initial on off sequence
            kineticSequenceQueue.add(new KineticSequence("turnOff", false));
            kineticSequenceQueue.add(new KineticSequence("turnOn", false));
        } catch (IOException ex) {
            System.out.println("Could not initialize relay components");
        }    
    }
    

    public void run() {
        Calendar calendar = Calendar.getInstance();
        int currentSecond = calendar.get(Calendar.SECOND);
        int currentMinute = calendar.get(Calendar.MINUTE);
        
        while (true) {
            // wait until the next second
            if(calendar.get(Calendar.SECOND)>currentSecond || calendar.get(Calendar.MINUTE) != currentMinute) {
                currentSecond = calendar.get(Calendar.SECOND);
                currentMinute = calendar.get(Calendar.MINUTE);
                
                updateSensors();
                
                // should check here whether new sequences need to be added to the queue.... or should that get done by control?
                
                executeQueue();
            }
//            runDiagonalChase();
//            runFlowerChase();
//            runAllOnOff();
//            runInputTest();
            
           updateSensors();
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
     * This method should create all triangles big and small and populate the
     * triangle / relay related tables.
     */
    static public void initializeFlowers() {
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                flowers[level][flowerNumber] = new Flower(level, flowerNumber, relayTable);
            }
        }

        // initialise the rest of the relayTable
        // PSU's and unsed 7th relay
        for (int i = 0; i < 19; i++) {
            relayTable[i][0] = new Relay();
            relayTable[i][7] = new Relay();
        }
        // bank 1
        for (int i = 1; i < 7; i++) {
            relayTable[0][i] = new Relay();
        }
    }

    static public void send(int i) {
        try {
            relayOutputStream.write(i);
        } catch (IOException ex) {
            ex.getMessage();
        }
    }

    static public int readLine(byte[] bytes) {
        int numberBytesRead = 0;
        try {
            int a = relayInputStream.available();
            numberBytesRead = relayInputStream.read(bytes);

        } catch (IOException ex) {
            ex.getMessage();
        }
        return numberBytesRead;
    }

    static public void sleep(int time) {
        try {
            Thread.currentThread().sleep(time);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
    
    static public void incrementVariable(HashMap<String, Object> ks, String key, int increment) {
        int oldVal = (int)ks.get(key);
        int newVal = oldVal + increment;
        ks.put(key, newVal);
    }
    
    static public void clear() {
        for (int bank = 1; bank < 19; bank++) {
            turnOffBank(bank);
        }
    }
    
    static public void updateRelays() {
        for (int bank = 0; bank < 19; bank++) {
            int command = 0;
            for (int relay = 0; relay < 8; relay++) {
                if (relayTable[bank][relay].getState()) {
                    command += Math.pow(2, relay);
                }
            }
            sleep(10);
            send(254);
            send(140);
            send(command);
            send(bank + 1); // +1 because ProXR starts at 1
            sleep(8);
        }
    }
    
    static public void updateSensors() {
        while(true) {
        try {
            relayInputStream.skip(relayInputStream.available());
        } catch (IOException ex) {
            ex.getMessage();
        }
        send(254);
        send(192);
        sleep(1000); // this is a rather long wait, will have ot experiment to find a suitable time
        byte[] bytes = new byte[sensors[1].length];
        int numberBytesRead = readLine(bytes);
        for(int i=0; i<bytes.length; i++) {
            int newValue = ((Byte)new Byte(bytes[i])).intValue() + 128; // adding 128 because we get a value ranging from -128 to 128
            System.out.println(Integer.toString(i) + " is: " + Integer.toString(newValue));
            if(Math.abs(sensors[0][i]-newValue)<6) { // Have set 6 to be the threshold for the sensor value changing
                sensors[1][i]=0;
                sensors[0][i]=newValue;
            } else {
                sensors[1][i]=1;
                sensors[0][i]=newValue;
            }
        }
        sleep(1000); // this is a rather long wait, will have ot experiment to find a suitable time
        }
    }
   
    static public void turnOnBank(int bank) {
        for (int relayNumber = 1; relayNumber < 8; relayNumber++) {
            relayTable[bank][relayNumber].setState(true);
        }
    }

    static public void turnOffBank(int bank) {
        for (int relayNumber = 1; relayNumber < 8; relayNumber++) {
            relayTable[bank][relayNumber].setState(false);
        }
    }
    
    static public void updateRelayStrokes() {
//        Properties status = new Properties();
//
//        try {
//            // load the status file
//            FileInputStream strokesFI = new FileInputStream("C:\\ControlV2\\src\\controlv2\\strokes.properties");
//            status.load(strokesFI);
//          
//            // Update misc. fields
//            // status.setProperty("activityLevel", Integer.toString(activityLevel));
//            
    }

    /**
     * When override is called totem should stop any sequence and run the override command.
     * 
     * @param input 
     */
    static public void override(String input) {
        clear(); // reset all relay values
        kineticSequenceQueue.clear(); // clear the queue
        String[] overrideCommands = input.split(",");
        for(int i=0; i<overrideCommands.length; i++) {
            String[] command = overrideCommands[i].split("-");
            int bank = Integer.parseInt(command[0]);
            String binaryCommand = Integer.toBinaryString(Integer.parseInt(command[0]));
        }
        updateRelays();
    }
    
    static public void executeQueue() {
        KineticSequence head = kineticSequenceQueue.peek();
        if (head!=null) {
            if(head.sequenceName.equals("runDiagonalChase")) {runDiagonalChase(head);} 
            else if (head.sequenceName.equals("runAllOnOff")) {runAllOnOff(head);}
            else if (head.sequenceName.equals("turnOn")) {turnOn(head);}
            else if (head.sequenceName.equals("turnOff")) {turnOff(head);}
            // if the sequence is finished then remove it
            if(ctrl.getActivityLevel() != -1) {
                if(head.finished) {kineticSequenceQueue.remove();}
            }
        } else {
            
            // no sequences in the queue
        }
    }

    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    /**
     * Start of sequences
     */
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    // -------------------------------------------------------------------------------------------- //
    
    // Should activate a coloumn of flowers corresponding to the PIR sesor below it.
    // Should also test rain wind and light sensors.
    static public void runInputTest() {
        boolean triggered = false;
        for (int i = 0; i < 6; i++) {
            if (sensors[0][i] > 128) { // the halfway value
                if (!flowers[0][i * 2 + 1].isInBloom()) {
                    triggered = true;
                    flowers[0][i * 2 + 1].allOn();
                    flowers[1][i * 2 + 1].allOn();
                    flowers[2][i * 2 + 1].allOn();
                }
            } else {
                if (flowers[0][i * 2 + 1].isInBloom()) {
                    triggered = true;
                    flowers[0][i * 2 + 1].allOff();
                    flowers[1][i * 2 + 1].allOff();
                    flowers[2][i * 2 + 1].allOff();
                }
            }
            if (triggered) {
                System.out.println("Sleeping sensors for 7 seconds");
                sleep(7000);
            } else {
                sleep(100);
            }
            
        }
    }

    static public void runDiagonalChase(KineticSequence ks) {
        // First initialise the map and fill it with vars needed for this method
        if (!ks.started) {
            ks.map = new HashMap<>();
            ks.map.put("stepNumber", 1);
            ks.started = true;
        }

        if (ks.map.get("stepNumber") == 1) {
            System.out.println("Starting diagonal chase");
            for (int level = 0; level < 3; level++) {
                turnOnBank(6 * level + 1);
                turnOnBank(6 * level + 6);
            }
            updateRelays();
        }

        if (ks.map.get("stepNumber") == 2) {
            for (int level = 0; level < 3; level++) {
                turnOffBank(6 * level + 1);
                turnOffBank(6 * level + 6);
            }
            for (int level = 0; level < 3; level++) {
                turnOnBank(6 * level + 5);
                turnOnBank(6 * level + 2);
            }
            updateRelays();
        }

        if (ks.map.get("stepNumber") == 3) {
            for (int level = 0; level < 3; level++) {
                turnOffBank(6 * level + 5);
                turnOffBank(6 * level + 2);
            }
            // horizontals
            for (int level = 0; level < 3; level++) {
                turnOnBank(6 * level + 4);
                turnOnBank(6 * level + 3);
            }
            updateRelays();
        }

        if (ks.map.get("stepNumber") == 4) {
            for (int level = 0; level < 3; level++) {
                turnOffBank(6 * level + 4);
                turnOffBank(6 * level + 3);
            }
            updateRelays();
            ks.finished = true;
        }

        incrementVariable(ks.map, "stepNumber", 1);
    }

    static public void runFlowerChase() {
        System.out.println("Starting chase bloom");
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                flowers[level][flowerNumber].allOn();
                updateRelays();
                sleep(1000);
            }
        }
    }

    
    // This sequence has not been finished converting
    static public void runAllOnOff(KineticSequence ks) {
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                flowers[level][flowerNumber].allOff();
            }
        }
        updateRelays();
        sleep(sleepTime);
        System.out.println("Starting all on off");
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                flowers[level][flowerNumber].allOn();
            }
        }
        updateRelays();
        sleep(sleepTime);
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                flowers[level][flowerNumber].allOff();
            }
        }
        updateRelays();
        ks.finished = true;
    }

    static public void turnOff(KineticSequence ks) {
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
        updateRelayStrokes();
        ks.finished = true;
    }

    static public void turnOn(KineticSequence ks) {
        // turn on all PSU's
        for (int bank = 0; bank < 19; bank++) {
            relayTable[bank][0].setState(true);
        }
        // relay coil 12V supply
        relayTable[0][1].setState(true);
        updateRelays();
        ks.finished = true;
    }

}
