/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package controlv2;

import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;
import javax.mail.*;
import javax.mail.internet.*;
import javax.activation.*;

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
    static MIDIController mctrl;
    static Boolean laserShowStarted = false;
    static Boolean laserShowRunning = false;
    static String logFilePath = "C:\\Totem logs\\";
    static File logFile;
    static FileWriter logFileWriter;
    static BufferedWriter logBufferedWriter;
    static Calendar calendar;
    static SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
    static SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss ");
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
        calendar = Calendar.getInstance();
        loadConfig(args[0]);
        commandFileModTime = new File(commandFileLoc).lastModified();
        
        Date date = new Date();
        
        long currentTime = System.currentTimeMillis();
        int statusUpdateTimeout = 30;
        int logFileTimeout = 60;
        
        // Act as if Totem is restarting after a failure so run sequence to turn off laser components
        startLaserShow(true);
        
        while(true) {
            
            // wait until the next second
            if(System.currentTimeMillis()-currentTime > 1000) {
                currentTime=System.currentTimeMillis();
                calendar = Calendar.getInstance();
                if(calendar.get(Calendar.HOUR_OF_DAY) >= startOfDay && activityLevel == 0) {
                    // turn on
                    activityLevel=1;
                    startOfDay();
                    log("Calling startOfDay");
                } else if (calendar.get(Calendar.HOUR_OF_DAY) == endOfDay && activityLevel == 1) {
                    // turn off
                    activityLevel=0;
                    // TODO: should empty rctrl's job queue and add a power off job
                } else if (calendar.get(Calendar.HOUR_OF_DAY) == 20 && calendar.get(Calendar.MINUTE) >= 30 && !laserShowStarted) {
                    startLaserShow(false);
                }
                readCommandFile();
                
                respondToWeather();
                
                // Only update the status file every 30 seconds.
                if(statusUpdateTimeout==0) {
                    updateStatus();
                    statusUpdateTimeout=10;
                } else {
                    statusUpdateTimeout--;
                }
                
                // Only update the log file every 60 seconds.
                if(logFileTimeout==0) {
                    log("Control is running");
                    logFileTimeout=60;
                } else {
                    logFileTimeout--;
                }
                
            }
        }
    }
    
    private static void startOfDay() {
        // generate 10 random dance times -- note sequences need a start time
        // 
        Long[] danceTimes = new Long[25*6];
        Calendar tempCalendar = Calendar.getInstance();
        
//        tempCalendar.add(Calendar.HOUR,1); 
//        randomizeTime(tempCalendar, 28);
        int i = 0;
        while(tempCalendar.get(Calendar.HOUR_OF_DAY) != endOfDay && i < danceTimes.length) {
            danceTimes[i] = new Long(tempCalendar.getTimeInMillis());
//            tempCalendar.add(Calendar.MINUTE,75);
//            randomizeTime(tempCalendar, 34);
            tempCalendar.add(Calendar.MINUTE,20);
            randomizeTime(tempCalendar, 4);
            i++;
        }
        
        rctrl.updateDanceTimes(danceTimes);
        
        // Reset laserShowStarted
        laserShowStarted = false;
    }
    
    public static void log(String logContent) {
        // If the log file doesn't exist or we're using the wrong days
        if (logFile == null || !logFile.getName().equals(dateFormat.format(calendar.getTime()) + ".txt")) {
            try {
                // close an already open file
                if(logFile!=null) {
                    logBufferedWriter.close();
                    
                    // Send Geoffrey an email here
                    email(logFile);
                    
                }
                // Change logFile to one with todays date as the file name
                logFile = new File(logFilePath + dateFormat.format(calendar.getTime()) + ".txt");
                if (!logFile.exists()) {
                    // Create new log file

                    logFile.createNewFile();

                }
                // Create FileWriter and BufferedWriter objects
                logFileWriter = new FileWriter(logFile.getAbsoluteFile(), true);
                logBufferedWriter = new BufferedWriter(logFileWriter);
            } catch (IOException ex) {
            }
        }

        // Write logContent to logFile and also push to system console
        try {
            String logLine = timeFormat.format(calendar.getTime()) + logContent + System.getProperty("line.separator");
            System.out.println(logLine);
            logBufferedWriter.write(logLine);
            logBufferedWriter.flush();
        } catch (IOException ex) {
        }
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
    
    // Creates a midicontroller to either run a show or the shutdown sequence 
    public static void startLaserShow(Boolean justShutdown) {
        mctrl = new MIDIController(ctrl, rctrl, justShutdown);
        mctrl.start();
        laserShowStarted = true;
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
        if(laserShowRunning) {
            // Check night sensor
        if(rctrl.sensors[0][6] < 254) {
            log("Exception: Shutting down laser show due to ambient light levels");
            startLaserShow(true);
        } else if (rctrl.sensors[0][7] > 254) {
            log("Exception: Shutting down laser show due to high wind levels");
            startLaserShow(true);
        }
        }
        
    }
    
    static public void sleep(int time) {
        try {
                Thread.sleep(time);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
    }
     
    static public void email(File logFile) {
        String SMTP_HOST_NAME = "mail.drake-brockman.com.au";
        String SMTP_PORT = "587";
        final String SMTP_FROM_ADDRESS = "totem@drake-brockman.com.au";
        String SMTP_TO_ADDRESS = "ruvan@ozemail.com.au";
        final String subject = "Totem Log";
        String fileAttachment = logFile.getAbsolutePath();

        Properties props = new Properties();

        props.put("mail.smtp.host", SMTP_HOST_NAME);
        props.put("mail.smtp.auth", "true");
        props.put("mail.debug", "true");
        props.put("mail.smtp.port", SMTP_PORT);

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                return new javax.mail.PasswordAuthentication(SMTP_FROM_ADDRESS, "108elements");
            }
        });

        try {
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(SMTP_FROM_ADDRESS));
            //create the message part
            MimeBodyPart messageBodyPart = new MimeBodyPart();
            //fill message
            messageBodyPart.setText("Attached is Totems log for the previous day");
            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(messageBodyPart);
            // Part two is an attachment
            messageBodyPart = new MimeBodyPart();
            FileDataSource source = new FileDataSource(fileAttachment);
            messageBodyPart.setDataHandler(new DataHandler(source));
            messageBodyPart.setFileName(fileAttachment);
            multipart.addBodyPart(messageBodyPart);
            //put part in message
            msg.setContent(multipart);
            msg.setRecipient(Message.RecipientType.TO, InternetAddress.parse(SMTP_TO_ADDRESS)[0]);
            msg.setSubject(subject);
            //msg.setContent(content, "text/plain");

            Transport.send(msg);
            System.out.println("Email Sent");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
