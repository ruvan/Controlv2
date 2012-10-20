/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

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
