/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

import java.io.*;
import java.util.*;

/**
 *
 * @author Tyrone
 */
public class MIDIController extends Thread {
    
    RelayController rctrl;
    String MIDIFilePath;
    String javaPath = "C:\\Program Files\\Java\\jre7\\bin\\java.exe";
    Process p;
    
    public MIDIController(RelayController rctrl) {
        this.rctrl = rctrl;
        File midiFolder = new File("C:\\midiFiles");
        File[] matchingFiles = midiFolder.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("midi") && name.endsWith("txt");
            }
        });
        Random randomGenerator = new Random();
        MIDIFilePath = matchingFiles[randomGenerator.nextInt(matchingFiles.length)].getAbsolutePath();
    }
    
    public void run() {
        rctrl.turnOnProjectionDoorPower();
        sleep(1000);
        rctrl.openProjectionDoor();
        sleep(30000);
        rctrl.turnOnLasers();
        sleep(30000);
        ProcessBuilder pb = new ProcessBuilder(javaPath, "-jar", "C:\\Control\\dist\\control.jar", MIDIFilePath);
        pb.directory(new File("C:\\"));
        try {
            p = pb.start();
        } catch (IOException ioex) {
        }
        try {
            p.waitFor();
        } catch (InterruptedException fr) {   
        }
        sleep(30000);
        rctrl.turnOffLasers();
        sleep(20000);
        rctrl.closeProjectionDoor();
        sleep(30000);
        rctrl.turnOffProjectionDoorPower();
        
    }
    
    
    static public void sleep(int time) {
        try {
            Thread.currentThread().sleep(time);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
    
}
