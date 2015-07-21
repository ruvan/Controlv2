package controlv2;

import java.io.*;
import java.util.*;

/**
 *
 * @author Ruvan Muthu-Krishna
 */
public class MIDIController extends Thread {
    
    Controlv2 ctrl;
    RelayController rctrl;
    String MIDIFilePath;
    String MIDIStopFilePath = "C:\\MIDI_Shutdown.txt";
    String javaPath = "C:\\Program Files\\Java\\jre7\\bin\\java.exe";
    Process p;
    Process off;
    Boolean justShutdown;
    
    public MIDIController(Controlv2 ctrl, RelayController rctrl, Boolean justShutdown) {
        this.ctrl = ctrl;
        this.rctrl = rctrl;
        this.justShutdown = justShutdown;
        if (!justShutdown) {
            ctrl.log("Laser show initiating");
            File midiFolder = new File("C:\\MIDI files");
            File[] matchingFiles = midiFolder.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith("midi") && name.endsWith("txt");
                }
            });
            Random randomGenerator = new Random();
            MIDIFilePath = matchingFiles[randomGenerator.nextInt(matchingFiles.length)].getAbsolutePath();
            ctrl.log("Laser show chosen: " + MIDIFilePath);
        }
    }
    
    /**
     * Excute the Control.jar file with the chosen midi file as the argument.
     * The control.jar file will play the midi file while this thread waits for it to finish execution.
     * 
     * TODO: Look at moving the functionality of control.jar into this codebase.
     */
    public void run() {
        if (!justShutdown) {
            startup();
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-jar", "C:\\Control\\dist\\Control.jar", MIDIFilePath);
            pb.directory(new File("C:\\"));
            ctrl.log("Playing laser show");
            try {
                ctrl.laserProcess = pb.start();
                ctrl.log("Laser show process started");
            } catch (IOException ioex) {
                ctrl.log("Laser show process failed to start");
            }
            try {
                ctrl.laserProcess.waitFor();
                ctrl.log("Successfully waited for Laser show process to finish.");
            } catch (InterruptedException fr) {
                ctrl.log("Failed to wait for Laser show process to finish.");
            }
            ctrl.log("Laser show finished");
        }
        shutdown();
        
    }
    
    /**
     * Run the laser start up routine
     */
    public void startup() {
        ctrl.laserShowRunning = true;
        rctrl.turnOnProjectionDoorPower();
        sleep(1000);
        rctrl.openProjectionDoor();
        sleep(30000);
        rctrl.turnOnLasers();
        sleep(30000);
    }
    
    /**
     * Shut down the laser show by sending the 'stop' midi file to the laser computers
     */
    public void shutdown() {
        ctrl.log("Shutting down laser show");
        ctrl.log("Running MIDI Stop File"); // run turn off midi
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
        
        rctrl.turnOffLasers();
        sleep(20000);
        rctrl.closeProjectionDoor();
        sleep(30000);
        rctrl.turnOffProjectionDoorPower();
        ctrl.laserShowRunning = false;
    }
    
    /** 
     * Sleep the tread for time milliseconds
     * @param time 
     */
    static public void sleep(int time) {
        try {
            Thread.currentThread().sleep(time);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
    
}
