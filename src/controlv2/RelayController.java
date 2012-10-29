package controlv2;

import java.io.*;
import gnu.io.*;
import java.util.*;
import java.math.*;

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
    static PriorityQueue<KineticSequence> kineticSequenceQueue = new PriorityQueue<KineticSequence>(10, queueComparator);    static Queue<Long> danceTimes = new LinkedList<>();
    static long lastReactionTime = 0;
    static int reactionTimeout = 5000;
    static int reactionsPerHour = 4;
    static long lastQueueExecutionTime = 0;
    static long queueExecutionTimeout = 10000;
    static Random randomGenerator = new Random();
    static Date date = new Date();
    static Calendar calendar = Calendar.getInstance();
    static int numberOfStills = 14;
    
    // Triangle / Relay
    static Relay[][] relayTable = new Relay[19][8];
    static Flower[][] flowers = new Flower[3][12];
    static int[][] sensors = new int[2][25];

    public RelayController(Controlv2 ctrl, String port, int baud, String programName) {
        this.ctrl = ctrl;
        try {
            relaySerialPort = initializeSerial(port, baud, programName);
            relayInputStream = relaySerialPort.getInputStream();
            relayOutputStream = relaySerialPort.getOutputStream();
            initializeFlowers();
            
            // queue the initial on off sequence
            kineticSequenceQueue.add(new KineticSequence("turnOff", false, false));
            kineticSequenceQueue.add(new KineticSequence("turnOn", false, false));
            executeQueue();
            executeQueue();
        } catch (IOException ex) {
            System.out.println("Could not initialize relay components");
        }    
    }
    

    public void run() {
        
        int numReactions = 0;
        
        while (true) {
                if (danceTimes.peek() != null && System.currentTimeMillis() > danceTimes.peek().longValue()) {
                danceTimes.remove(); // remove element from queue
                // fill kinetic sequence queue
                for(int i=0; i<10; i++) {
                    switch (randomGenerator.nextInt(numberOfStills-1)) {
                        case 0:
                            kineticSequenceQueue.add(new KineticSequence("runRightDiagonals", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 1:
                            kineticSequenceQueue.add(new KineticSequence("runLeftDiagonals", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 2:
                            kineticSequenceQueue.add(new KineticSequence("runHorizontals", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 3:
                            kineticSequenceQueue.add(new KineticSequence("runBothDiagonals", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 4:
                            kineticSequenceQueue.add(new KineticSequence("runLeftDandH", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 5:
                            kineticSequenceQueue.add(new KineticSequence("runRightDandH", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 6:
                            kineticSequenceQueue.add(new KineticSequence("runBlank", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 7:
                            kineticSequenceQueue.add(new KineticSequence("runHugeTriangle", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;
                        case 8:
                            KineticSequence ks8 = new KineticSequence("runChaos", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean());
                            ks8.map=new HashMap<>();
                            ks8.map.put("chaosType", 1);
                            kineticSequenceQueue.add(ks8);
                            break;
                        case 9:
                            KineticSequence ks9 = new KineticSequence("runChaos", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean());
                            ks9.map=new HashMap<>();
                            ks9.map.put("chaosType", 2);
                            kineticSequenceQueue.add(ks9);
                            break;
                        case 10:
                            KineticSequence ks10 = new KineticSequence("runChaos", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean());
                            ks10.map=new HashMap<>();
                            ks10.map.put("chaosType", 3);
                            kineticSequenceQueue.add(ks10);
                            break;
                        case 11:
                            KineticSequence ks11 = new KineticSequence("runCheckerBoard", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean());
                            ks11.map=new HashMap<>();
                            ks11.map.put("orientation", true);
                            kineticSequenceQueue.add(ks11);
                            break;
                        case 12:
                            KineticSequence ks12 = new KineticSequence("runCheckerBoard", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean());
                            ks12.map = new HashMap<>();
                            ks12.map.put("orientation", false);
                            kineticSequenceQueue.add(ks12);
                            break;
                        case 13:
                            kineticSequenceQueue.add(new KineticSequence("runAllBloom", false, randomGenerator.nextBoolean() && randomGenerator.nextBoolean()));
                            break;

                    }
                }
                
            }
                
            updateSensors();
            
            // react to inputs
            // maybe wait if a reaction has taken place
            // react to sensors should queue a new sequence to the front of the queue
            if (System.currentTimeMillis() > (lastReactionTime + reactionTimeout) && numReactions <= reactionsPerHour && (kineticSequenceQueue.isEmpty() || !kineticSequenceQueue.peek().isReaction)) {
                reactionTimeout+=2000;
                lastReactionTime = System.currentTimeMillis();
                numReactions++;
                reactToSensors();
            }
            
            // reset numReactions every hourish
            if (calendar.get(calendar.MINUTE) == 5 || calendar.get(calendar.HOUR_OF_DAY) == 5 ) {
                reactionTimeout = 5000;
                numReactions = 0;
            }
            
            // if it's been queueExecutionTimeout since queue execution then execute
            // such a structure currently will not work well with moving sequences unless we put || head.started in the if clause
            if (System.currentTimeMillis() > lastQueueExecutionTime + queueExecutionTimeout || (!kineticSequenceQueue.isEmpty() && kineticSequenceQueue.peek().started)) {
                executeQueue();
                lastQueueExecutionTime = System.currentTimeMillis();
            }
            
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
    
    static public void updateDanceTimes(Long[] times) {
        // in place of this for loop, danceTimes.addAll() can be used but requres times to be Long[] rather than long[]
        
            for (int i=0; i<times.length; i++) {
                danceTimes.add(times[i]);
                date.setTime(times[i]);
                System.out.println("Dance time " + i + " = " + date.toString());
            }
    }
    
    static public void reactToSensors() {
        // find if we had a triggered sensor and add it to an arraylist 
        ArrayList list = new ArrayList();
        for (int i = 0; i < 6; i++) {
            if (sensors[0][i] > 254 && randomGenerator.nextBoolean()) {
                list.add(i); // note should be passing an Integer rather than an int here
            }
        }
        
        // no longer needed as randomGenerator.nextBoolean() is in the statement above.
//        // Randomly cull the list
//        int numberTriggeredSensors = list.size();
//        for (int i=0; i<numberTriggeredSensors; i++) {
//            int sensorNumber = randomGenerator.nextInt(list.size()-1);
//            if (randomGenerator.nextBoolean()) {
//                list.remove(sensorNumber);
//            }
//        }
        
        // Should randomly choose a reaction sequence here
        if (list.size() > 0) {
            int reactionSelect = randomGenerator.nextInt(3);
            KineticSequence ks = null;
            boolean add = randomGenerator.nextBoolean();
            switch(reactionSelect) {
                case 0: ks = new KineticSequence("runPetalPropogation", true, add);
                    break;
                case 1: ks = new KineticSequence("runBeaksPropogation", true, add); 
                    break;
                case 2: ks = new KineticSequence("runBloomPropogation", true, add); 
                    break;
                case 3: ks = new KineticSequence("runAllBloom", true, add);
                    break;
            }
            ks.map = new HashMap<>();
            ks.map.put("sensors", list);
            kineticSequenceQueue.add(ks);
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
    
    static public void invert() {
        for (int bank = 1; bank < 19; bank++) {
            for (int relay = 1; relay < 8; relay++) {
                relayTable[bank][relay].toggleState();
            }
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
        
        try {
            relayInputStream.skip(relayInputStream.available());
        } catch (IOException ex) {
            ex.getMessage();
        }
        send(254);
        send(204);
        sleep(1000); // this is a rather long wait, will have ot experiment to find a suitable time
        byte[] bytes = new byte[sensors[1].length];
        int numberBytesRead = readLine(bytes);
//        System.out.println("bytes read: " + Integer.toString(numberBytesRead));
        for(int i=1; i<bytes.length; i+=2) {
           
            byte[] tempByte2 = new byte[2];
            tempByte2[0]=bytes[i];
            tempByte2[1]=bytes[i+1];
            BigInteger bigInt = new BigInteger(tempByte2);
            
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%02X", bytes[i+1]));
                sb.append(String.format("%02X", bytes[i]));
            
            //System.out.println(sb.toString());
            int tempInt = bytes[i] << 8 | bytes[i+1];
            
//            System.out.println(Integer.toString((int)(i*0.5)) + " is: " + Integer.toString(bigInt.intValue()));
            System.out.println(Integer.toString((int)((i-1)*0.5)) + " is: " + Integer.toString(tempInt) + " - " + sb.toString());
//            System.out.printf("%02X", bytes[i]);
            if(Math.abs(sensors[0][i]-bigInt.intValue()) > 6) { // we consider the sensor state changed
                sensors[0][i] = bigInt.intValue();
                sensors[1][i] = 1;
            } else {
                sensors[1][i] = 0;
            }
                
        }
//        sleep(1000); // this is a rather long wait, will have ot experiment to find a suitable time
        
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
    
    static public void writeBinaryStringToBank(int bank, int number) {
        String binaryString = Integer.toBinaryString(number);
        for (int i = 0; i < binaryString.length(); i++) {
            if(binaryString.charAt(i) == '0') {
                relayTable[bank-1][i].setState(false);
            } else {
                relayTable[bank-1][i].setState(true);
            }
        }
    }
    
    static public ArrayList getAdjacentFlowers(int level, int flowerNumber) {
        ArrayList adjacent = new ArrayList();
        switch(flowerNumber) {
            case 0:
                adjacent.add(flowers[level][1]);
                adjacent.add(flowers[level][11]);
                break;
            case 11:
                adjacent.add(flowers[level][10]);
                adjacent.add(flowers[level][0]);
                break;
            default: 
                adjacent.add(flowers[level][flowerNumber+1]);
                adjacent.add(flowers[level][flowerNumber-1]);
        }
        
        // Can't remeber here if triangle 0 (1) is is upper or lowwer
        switch(level) {
            case 0: 
                if(flowerNumber%2 != 0) { // have an odd flower
                    adjacent.add(flowers[level+1][flowerNumber]);
                }
                break;
            case 1:
                if(flowerNumber%2 != 0) { // have an odd flower
                    adjacent.add(flowers[level-1][flowerNumber]);
                } else {
                    adjacent.add(flowers[level+1][flowerNumber]);
                }
                break;
            case 2:
                if(flowerNumber%2 == 0) { // have an odd flower
                    adjacent.add(flowers[level-1][flowerNumber]);
                }
                break;
        }
        return adjacent;
    }
    
    static public void toggleBank(int bank) {
        for (int relayNumber = 1; relayNumber < 8; relayNumber++) {
            relayTable[bank][relayNumber].toggleState();
        }
    }
    
    static public Petal getBeakPetal(Flower flower, Petal petal) {
        if((flower.level == 2 && petal.orientation == 4) || (flower.level == 0 && petal.orientation == 5)) {
            return null;
        } else {
            int flowerNumber;
            switch (petal.orientation) {
                case 2:
                    if(flower.flowerNumber==11) {
                        flowerNumber = 0;
                    } else {
                        flowerNumber = flower.flowerNumber+1;
                    }
                    return flowers[flower.level][flowerNumber].petals[1];
                case 3:
                    if(flower.flowerNumber==11) {
                        flowerNumber = 0;
                    } else {
                        flowerNumber = flower.flowerNumber+1;
                    }
                    return flowers[flower.level][flowerNumber].petals[2];
                case 4:
                    return flowers[flower.level+1][flower.flowerNumber].petals[0];
                case 5:
                    return flowers[flower.level-1][flower.flowerNumber].petals[0];
                case 6:
                    if(flower.flowerNumber==0) {
                        flowerNumber = 11;
                    } else {
                        flowerNumber = flower.flowerNumber-1;
                    }
                    return flowers[flower.level][flowerNumber].petals[2];
                case 7:
                    if(flower.flowerNumber==0) {
                        flowerNumber = 11;
                    } else {
                        flowerNumber = flower.flowerNumber-1;
                    }
                    return flowers[flower.level][flowerNumber].petals[1];
            }
        }
        
        return null;
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
            writeBinaryStringToBank(Integer.parseInt(command[0]),Integer.parseInt(command[1]));
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
            else if (head.sequenceName.equals("runInputTest")) {runInputTest();}
            else if (head.sequenceName.equals("runPetalPropogation")) {runPetalPropogation(head);}
            else if (head.sequenceName.equals("runBeaksPropogation")) {runBeaksPropogation(head);}
            else if (head.sequenceName.equals("runBloomPropogation")) {runBloomPropogation(head);}
            else if (head.sequenceName.equals("runRightDiagonals")) {runRightDiagonals(head);}
            else if (head.sequenceName.equals("runLeftDiagonals")) {runLeftDiagonals(head);}
            else if (head.sequenceName.equals("runHorizontals")) {runHorizontals(head);}
            else if (head.sequenceName.equals("runBothDiagonals")) {runBothDiagonals(head);}
            else if (head.sequenceName.equals("runLeftDandH")) {runLeftDandH(head);}
            else if (head.sequenceName.equals("runRightDandH")) {runRightDandH(head);}
            else if (head.sequenceName.equals("runBlank")) {runBlank(head);}
            else if (head.sequenceName.equals("runHugeTriangle")) {runHugeTriangle(head);}
            else if (head.sequenceName.equals("runChaos")) {runChaos(head);}
            else if (head.sequenceName.equals("runCheckerBoard")) {runCheckerBoard(head);}
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
    
    
    // -------------------------------------------------------------------------------------------- //
    // Reactions
    static public void runPetalPropogation(KineticSequence ks) {
        System.out.println("running petal prop ");
        ks.isReaction = true;
        ks.started = true;
        ArrayList sensorList = (ArrayList)ks.map.get("sensors");
        
       if(!ks.started) {
           // create a timings table
       }
       ks.finished=true;
        
    }
    
    // unfinished
    static public void runBeaksPropogation(KineticSequence ks) {
        System.out.println("running beaks prop ");
        if(ks.add){System.out.println("adding");}
        ks.isReaction = true;
        ks.started = true;
        ArrayList sensorList = (ArrayList)ks.map.get("sensors");
        ks.finished=true;
    }
    
    // bloom propagation will not toggle, it will bloom
    static public void runBloomPropogation(KineticSequence ks) {
        System.out.println("running bloom prop ");
        ks.isReaction = true;
        long[][][] timings = null;
        ArrayList sensorList = (ArrayList)ks.map.get("sensors");
        
       if(!ks.started) {
           // create a timings table
           timings = new long[3][12][2];
           while(!sensorList.isEmpty()) {
               int flowerNumber = (int)sensorList.remove(0) * 2;
               flowers[0][flowerNumber].togglePetals();
               timings[0][flowerNumber][0] = System.currentTimeMillis();
               timings[0][flowerNumber][1] = 1;
           }
           ks.map.put("timings", timings);
           ks.started = true;
       } else {
           timings = (long[][][])ks.map.get("timings");
       }
       
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                if (timings[level][flowerNumber][1] == 1) {
                    if (System.currentTimeMillis() > timings[level][flowerNumber][0] + 2000) {
                        ArrayList adjacentFlowers = getAdjacentFlowers(level, flowerNumber);
                        while (!adjacentFlowers.isEmpty()) {
                            Flower flower = (Flower) adjacentFlowers.remove(0);
                            if (timings[flower.level][flower.flowerNumber][1] == 0) {
                                flower.togglePetals();
                                timings[flower.level][flower.flowerNumber][1] = 1;
                                timings[flower.level][flower.flowerNumber][0] = System.currentTimeMillis();
                            }
                        }
                    }
                    if(System.currentTimeMillis() > timings[level][flowerNumber][0] + 10000) {
                        // toggle back
                        flowers[level][flowerNumber].togglePetals();
                        timings[level][flowerNumber][1] = 2;
                        timings[level][flowerNumber][0] = System.currentTimeMillis();
                    }
                } else if (timings[level][flowerNumber][1] == 2 && System.currentTimeMillis() > timings[level][flowerNumber][0] + 10000) {
                    ArrayList adjacentFlowers = getAdjacentFlowers(level, flowerNumber);
                    while (!adjacentFlowers.isEmpty()) {
                        Flower flower = (Flower)adjacentFlowers.remove(0);
                        if(timings[flower.level][flower.flowerNumber][1] == 1) {
                            flower.togglePetals();
                            timings[flower.level][flower.flowerNumber][1] = 2;
                            timings[flower.level][flower.flowerNumber][0] = System.currentTimeMillis();
                        }
                    }
                }
            }
        }
       
        boolean allAt2 = true;
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                if(timings[level][flowerNumber][1] != 2){
                    allAt2 = false;
                }
            }
        }
        
        updateRelays();
        
        if(allAt2) {
            ks.finished = true;
        } else{
            ks.map.put("timings", timings);
        }
    }
    
    static public void runAllBloom(KineticSequence ks) {
        System.out.println("running all bloom");
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                flowers[level][flowerNumber].allOn();
            }
        }
        updateRelays();
        ks.finished = true;
    }
    
    // -------------------------------------------------------------------------------------------- //
    // Stills and sequences
    
    // Should activate a coloumn of flowers corresponding to the PIR sesor below it.
    // Should also test rain wind and light sensors.
    static public void runInputTest() {
        boolean triggered = false;
        for (int i = 0; i < 6; i++) {
            if (sensors[0][i] > 64) { // the halfway value
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
                updateRelays();
//                System.out.println("Sleeping sensors for 7 seconds");
//                sleep(7000);
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
        System.out.println("running turn off");
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
        System.out.println("running turn on");
        // turn on all PSU's
        for (int bank = 0; bank < 19; bank++) {
            relayTable[bank][0].setState(true);
        }
        // relay coil 12V supply
        relayTable[0][1].setState(true);
        updateRelays();
        ks.finished = true;
    }
    
    static public void runRightDiagonals(KineticSequence ks) {
        System.out.println("running right diag");
        if(!ks.add) {clear();}
        for (int level = 0; level < 3; level++) {
                toggleBank(6 * level + 1);
                toggleBank(6 * level + 6);
        }
        ks.finished=true;
        updateRelays();
    }
    
    static public void runLeftDiagonals(KineticSequence ks) {
        System.out.println("running left diag");
        if(!ks.add) {clear();}
        for (int level = 0; level < 3; level++) {
                toggleBank(6 * level + 2);
                toggleBank(6 * level + 5); 
        }
        ks.finished=true;
        updateRelays();
    }
    
    static public void runHorizontals(KineticSequence ks) {
        System.out.println("running horizontals");
        if(!ks.add){clear();}
        for (int level = 0; level < 3; level++) {
            
                toggleBank(6 * level + 3);
                toggleBank(6 * level + 4);
            
        }
        ks.finished=true;
        updateRelays();
    }
    
    static public void runBothDiagonals(KineticSequence ks) {
        System.out.println("running both diag");
        KineticSequence r = new KineticSequence("runRightDiagonals", false, ks.add);
        KineticSequence l = new KineticSequence("runLeftDiagonals", false, true);
        runRightDiagonals(r);
        runLeftDiagonals(l);
        updateRelays();
        ks.finished = true;
    }
    
    static public void runLeftDandH(KineticSequence ks) {
        System.out.println("running left d and h");
        KineticSequence h = new KineticSequence("runHorizontals", false, ks.add);
        KineticSequence l = new KineticSequence("runLeftDiagonals", false, true);
        runHorizontals(h);
        runLeftDiagonals(l);
        updateRelays();
        ks.finished = true;
    }
    
    static public void runRightDandH(KineticSequence ks) {
        System.out.println("running right d and h");
        KineticSequence h = new KineticSequence("runHorizontals", false, ks.add);
        KineticSequence r = new KineticSequence("runRightDiagonals", false, true);
        runHorizontals(h);
        runRightDiagonals(r);
        updateRelays();
        ks.finished = true;
    }
    
    static public void runBlank(KineticSequence ks) {
        System.out.println("running blank");
        clear();
        updateRelays();
        ks.finished=true;
    }
    
    // Not finished
    static public void runHugeTriangle(KineticSequence ks) {
        System.out.println("running huge triangle");
        ks.finished = true;
    }
    
    
    static public void runChaos(KineticSequence ks) {
        System.out.println("running chaos");
        clear();
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                switch ((int) ks.map.get("chaosType")) {
                    case 1:
                        for (int petalNumber = 0; petalNumber < 3; petalNumber++) {
                            if (randomGenerator.nextBoolean() && randomGenerator.nextBoolean()) {
                                flowers[level][flowerNumber].petals[petalNumber].relay.setState(true);
                            }
                        }
                        break;
                    case 2:
                        for (int petalNumber = 0; petalNumber < 3; petalNumber++) {
                            if (randomGenerator.nextBoolean() && randomGenerator.nextBoolean() && randomGenerator.nextBoolean()) {
                                flowers[level][flowerNumber].petals[petalNumber].relay.setState(true);
                                Petal beakPetal = getBeakPetal(flowers[level][flowerNumber], flowers[level][flowerNumber].petals[petalNumber]);
                                if(beakPetal!=null) {beakPetal.relay.setState(true);}
                            }
                        }
                        break;
                    case 3:
                        if(randomGenerator.nextBoolean() && randomGenerator.nextBoolean()) {
                            flowers[level][flowerNumber].allOn();
                        }
                }

            }
        }
        updateRelays();
        ks.finished=true;
    }
    
    static public void runCheckerBoard(KineticSequence ks) {
        System.out.println("running checkerboard");
        clear();
        for (int level = 0; level < 3; level++) {
            for (int flowerNumber = 0; flowerNumber < 12; flowerNumber++) {
                if(level%2 == 0 && flowerNumber%2 ==0) {
                    flowers[level][flowerNumber].allOn();
                } else if (level%2 != 0 && flowerNumber%2 != 0) {
                    flowers[level][flowerNumber].allOn();
                }
            }
        }
        
        if((Boolean)ks.map.get("orientation")) {
            invert();
        }
        updateRelays();
        ks.finished=true;
    }
            

}
