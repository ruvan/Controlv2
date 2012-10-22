/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

import java.util.Properties;
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

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        ctrl = new Controlv2();
        loadConfig(args[0]);
        
        while (true) {
            try {
                Thread.sleep(100);
                updateStatus();
                
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
        
    }

    private static void loadConfig(String configPath) {
        Properties prop = new Properties();

        try {
            // load the properties file
            //FileInputStream propertiesFile = new FileInputStream("C:\\Control\\src\\control\\config.properties");
            FileInputStream propertiesFile = new FileInputStream(configPath);
            prop.load(propertiesFile);

            programName = prop.getProperty("ProgramName");
            statusFileLoc = prop.getProperty("statusFileLoc");
            commandFileLoc = prop.getProperty("commandFileLoc");

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
        try {
            // load the status file
            FileInputStream commandFile = new FileInputStream(commandFileLoc);
            DataInputStream in = new DataInputStream(commandFile);
            BufferedReader commandReader = new BufferedReader(new InputStreamReader(in));
            String commandLine;
            
            while((commandLine = commandReader.readLine()) != null) {
                String[] split = commandLine.split("=");
                if(split[0].equals("r")) { // we have a relay override command
                    rctrl.override(split[1]);
                }
            }
            
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    static public void updateStatus() {
        Properties status = new Properties();

        try {
            // load the status file
            FileInputStream statusFile = new FileInputStream(statusFileLoc);
            status.load(statusFile);

            // Update relay status
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
                status.setProperty("b," + Integer.toString(bank + 1), command);
            }
            
            // Update sensor status
            for (int i=0; i<rctrl.sensors.length; i++) {
                Byte temp = new Byte(rctrl.sensors[0][i]);
                if(i<6) {
                    status.setProperty("s,m," + Integer.toString(i + 1), temp.toString());
                } else if(i<8) {
                    status.setProperty("s,w," + Integer.toString(i - 5), temp.toString());
                } else if(i==8) {
                    status.setProperty("s,r", temp.toString());
                } else {
                    status.setProperty("s,l", temp.toString());
                }
            }

            // close the status file
            statusFile.close();

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    static public void sleep(int time) {
        try {
                Thread.sleep(time);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
    }
     

}
