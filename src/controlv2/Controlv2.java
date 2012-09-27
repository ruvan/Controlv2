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

}
