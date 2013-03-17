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
    
    Controlv2 ctrl;
    RelayController rctrl;
    String MIDIFilePath;
    String MIDIStopFilePath = "C:\\midi\\stopfile.midi";
    String javaPath = "C:\\Program Files\\Java\\jre7\\bin\\java.exe";
    Process p;
    Process off;
    
    public MIDIController(Controlv2 ctrl, RelayController rctrl) {
        this.ctrl = ctrl;
        this.rctrl = rctrl;
        ctrl.log("Laser show initiating");
        File midiFolder = new File("C:\\midiFiles");
        File[] matchingFiles = midiFolder.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("midi") && name.endsWith("txt");
            }
        });
        Random randomGenerator = new Random();
        MIDIFilePath = matchingFiles[randomGenerator.nextInt(matchingFiles.length)].getAbsolutePath();
        ctrl.log("Laser show chosen: " + MIDIFilePath);
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
        ctrl.log("Playing laser show");
        try {
            p = pb.start();
        } catch (IOException ioex) {
        }
        try {
            p.waitFor();
        } catch (InterruptedException fr) {   
        }
        ctrl.log("Laser show finished");
        
        // run turn off midi
        ctrl.log("Running MIDI Stop File");
        ProcessBuilder stopMidi = new ProcessBuilder(javaPath, "-jar", "C:\\Control\\dist\\control.jar", MIDIStopFilePath);
        stopMidi.directory(new File("C:\\"));
        try {
            off = stopMidi.start();
        } catch (IOException ioex) {
        }
        try {
            off.waitFor();
        } catch (InterruptedException fr) {   
        }
        ctrl.log("MIDI Stop File finished");
        
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
