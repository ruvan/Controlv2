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
        
        Calendar calendar = Calendar.getInstance();
        int currentSecond = calendar.get(Calendar.SECOND);
        int currentMinute = calendar.get(Calendar.MINUTE);
        
        while(true) {
            // wait until the next second
            if(calendar.get(Calendar.SECOND)>currentSecond || calendar.get(Calendar.MINUTE) != currentMinute) {
                currentSecond=calendar.get(Calendar.SECOND);
                currentMinute = calendar.get(Calendar.MINUTE);
                
                if(calendar.get(Calendar.HOUR_OF_DAY) > startOfDay-1 && activityLevel == 0) {
                    // turn on
                } else if (calendar.get(Calendar.HOUR_OF_DAY) > endOfDay-1 && activityLevel != -1) {
                    // turn off
                    activityLevel=0;
                    // should empty rctrl's job queue and add a power off job
                }
                readCommandFile();
                
                respondToWeather();
                
                updateStatus();
            }
        }
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
                    if (split[0].equals("r")) { // we have a relay override command
                        rctrl.override(split[1]);
                    }
                }

                commandFile.close();

            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
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
            
            // Update relay status
            System.out.println("updating relay status now");
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
                System.out.println(status.getProperty("b," + Integer.toString(bank + 1)));
                System.out.println("updating relay b," + Integer.toString(bank + 1) + " to " + command);
                status.setProperty("b," + Integer.toString(bank + 1), command);
            }
            
            // Update sensor status
            System.out.println("updating sensor status now");
            System.out.println("sensor array size: " + Integer.toString(rctrl.sensors[1].length));
            for (int i=0; i<rctrl.sensors[1].length; i++) {
                
                System.out.println("sensor " + Integer.toString(i) + " is " + Integer.toString(rctrl.sensors[0][i]));
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
