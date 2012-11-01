/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

import java.util.*;
import java.io.*;

/**
 *
 * @author Tyrone
 */
public class Controlv2 {

    /**
     * Class Variables
     */
    static String programName;
    static Controlv2 ctrl;
    static Boolean relay = false;
    static RelayController rctrl;
    static String statusFileLoc;
    static String commandFileLoc;
    Boolean debug = true;
    static long commandFileModTime;
    

    /**
     * Totem Behavioural Variables
     */
    static String mood;
    static int activityLevel;
    static int startOfDay;
    static int endOfDay;
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        ctrl = new Controlv2();
        loadConfig(args[0]);
        commandFileModTime = new File(commandFileLoc).lastModified();
        
        Date date = new Date();
        Calendar calendar = Calendar.getInstance();
        long currentTime = System.currentTimeMillis();
        
        while(true) {
            
            // wait until the next second
            if(System.currentTimeMillis()-currentTime > 1000) {
                currentTime=System.currentTimeMillis();
                calendar = Calendar.getInstance();
                if(calendar.get(Calendar.HOUR_OF_DAY) == startOfDay && activityLevel == 0) {
//                if(activityLevel == 0) {
                    // turn on
                    activityLevel=1;
                    startOfDay();
                    System.out.println("Calling startOfDay");
                } else if (calendar.get(Calendar.HOUR_OF_DAY) == endOfDay && activityLevel != -1) {
                    // turn off
                    activityLevel=0;
                    // should empty rctrl's job queue and add a power off job
                }
                readCommandFile();
                
                respondToWeather();
                
                updateStatus();
                System.out.println(activityLevel);
            }
        }
    }
    
    private static void startOfDay() {
        // generate 10 random dance times -- note sequences need a start time
        // 
        Long[] danceTimes = new Long[17*6];
        Calendar tempCalendar = Calendar.getInstance();
        
//        tempCalendar.add(Calendar.HOUR,1); 
//        randomizeTime(tempCalendar, 28);
        for (int i = 0; i < danceTimes.length; i++) {
            danceTimes[i] = new Long(tempCalendar.getTimeInMillis());
//            tempCalendar.add(Calendar.MINUTE,75);
//            randomizeTime(tempCalendar, 34);
            tempCalendar.add(Calendar.MINUTE,8);
            randomizeTime(tempCalendar, 4);
        }
        
        rctrl.updateDanceTimes(danceTimes);
    }
    
    private static void randomizeTime(Calendar tempCalendar, int number) {
        Random randomGenerator = new Random();
        int minutes = randomGenerator.nextInt(number);
        if(randomGenerator.nextBoolean()) {
            minutes = minutes*(-1);
        }
        tempCalendar.add(Calendar.MINUTE, minutes);
    }
    
    private static void loadConfig(String configPath) {
        Properties prop = new Properties();

        try {
            // load the properties file
            FileInputStream propertiesFile = new FileInputStream(configPath);
            prop.load(propertiesFile);

            programName = prop.getProperty("ProgramName");
            statusFileLoc = prop.getProperty("statusFileLoc");
            commandFileLoc = prop.getProperty("commandFileLoc");
            
            // Load behavioural vars from status file;
            mood = prop.getProperty("initialMood");
            activityLevel = Integer.parseInt(prop.getProperty("initialActivityLevel"));
            startOfDay = Integer.parseInt(prop.getProperty("startOfDay")); 
            endOfDay = Integer.parseInt(prop.getProperty("endOfDay"));
            
//            // MIDI vars
//            if (prop.getProperty("MIDI").equals("true")) {
//                initializeMidi(prop.getProperty("MIDIDeviceName"));
//                MIDI = true;
//            } else {
//                MIDI = false;
//            }

            // Relay vars
            if (prop.getProperty("Relay").equals("true")) {
                relay = true;
                rctrl = new RelayController(ctrl, prop.getProperty("RelayComPort"), Integer.parseInt(prop.getProperty("RelayBaud")), programName);
                rctrl.start();
            }

            // close the properties file
            propertiesFile.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    static void readCommandFile() {

        // Get the last modified time
        long modifiedTime = new File(commandFileLoc).lastModified();
        
        if (modifiedTime > commandFileModTime) {
            commandFileModTime = modifiedTime;
            try {
                // load the status file
                FileInputStream commandFile = new FileInputStream(commandFileLoc);
                DataInputStream in = new DataInputStream(commandFile);
                BufferedReader commandReader = new BufferedReader(new InputStreamReader(in));
                String commandLine;

                while ((commandLine = commandReader.readLine()) != null) {
                    String[] split = commandLine.split("=");
                    if (split[0].charAt(0) == 'r') { // we have a relay command
                        if (split[0].charAt(2) == 'o') { // we have a relay override command
                            activityLevel = -1;
                            rctrl.override(split[1]);
                        } else if (split[0].charAt(2) == 'a') { // we have a kinetic sequence to queue
                            if(split.equals("auto")) {
                                activityLevel = 1;
                            } else {
                                activityLevel = -1;
                            }
                            rctrl.kineticSequenceQueue.add(new KineticSequence(split[1], true, false));
                        }
                    }
                }

                commandFile.close();

            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
    
    public int getActivityLevel() {
        return activityLevel;
    }

    static public void updateStatus() {
        Properties status = new Properties();

        try {
            // load the status file
            FileInputStream statusFI = new FileInputStream(statusFileLoc);
            status.load(statusFI);
          
            // Update misc. fields
            status.setProperty("activityLevel", Integer.toString(activityLevel));
            status.setProperty("mood", mood);
            if(rctrl.kineticSequenceQueue.peek() == null) {
                status.setProperty("kineticSequence", "none");
            } else {
                status.setProperty("kineticSequence", rctrl.kineticSequenceQueue.peek().sequenceName);
                // Could get an iterator and iterate through the queue here to display queue elements in the UI
            }
            
            
            // Update relay status
//            System.out.println("updating relay status now");
            for (int bank = 0; bank < 19; bank++) {
                String command = "";
                for (int relay = 0; relay < 8; relay++) {
                    if (rctrl.relayTable[bank][relay].getState()) {
                        command += "1";
                    } else {
                        command += "0";
                    }
                }
                // set the status of the bank in the status file
//                System.out.println(status.getProperty("b," + Integer.toString(bank + 1)));
//                System.out.println("updating relay b," + Integer.toString(bank + 1) + " to " + command);
                status.setProperty("b," + Integer.toString(bank + 1), command);
            }
            
            // Update sensor status
//            System.out.println("updating sensor status now");
//            System.out.println("sensor array size: " + Integer.toString(rctrl.sensors[1].length));
            for (int i=0; i<rctrl.sensors[1].length; i++) {
                
//                System.out.println("sensor " + Integer.toString(i) + " is " + Integer.toString(rctrl.sensors[0][i]));
                if(i<6) {
                    status.setProperty("s,m," + Integer.toString(i + 1), Integer.toString(rctrl.sensors[0][i]));
                } else if(i<8) {
                    status.setProperty("s,w," + Integer.toString(i - 5), Integer.toString(rctrl.sensors[0][i]));
                } else if(i==8) {
                    status.setProperty("s,r", Integer.toString(rctrl.sensors[0][i]));
                } else {
                    status.setProperty("s,l", Integer.toString(rctrl.sensors[0][i]));
                }
            }

            // close the status file
            statusFI.close();
            FileOutputStream statusFO = new FileOutputStream(statusFileLoc);
            status.store(statusFO, null);
            statusFO.close();
            
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    static public void respondToWeather() {
        // Check wind speed, light and rain, turn off / on if necessary 
    }
    
    static public void sleep(int time) {
        try {
                Thread.sleep(time);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
    }
     

}
