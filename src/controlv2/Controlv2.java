package controlv2;

import java.util.*;
import java.io.*;
import java.text.SimpleDateFormat;
import javax.mail.*;
import javax.mail.internet.*;
import javax.activation.*;

/**
 *
 * @author Ruvan Muthu-Krishna
 * @version 2.0
 * 
 * Control code written for Geoffrey Drake-Brockman for his art works Totem and Translight
 */
public class Controlv2 {

    /**
     * Class Variables
     */
    static String programName;
    static Boolean relay = false;
    Boolean debug = true;
    
    /**
     * External File Variables
     */
    static String statusFileLoc;
    static String commandFileLoc;
    static String tempShowsFileLoc;
    static String showsFileLoc;
    static long commandFileModTime;
    static long showsFileModTime;
    
    /**
     * Calendar Variables
     */
    static Calendar calendar;
    static SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");
    static SimpleDateFormat timeFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss ");
    
    /** 
     * Object Variables
     */
    static Controlv2 ctrl;
    static MIDIController mctrl;
    static RelayController rctrl;
    
    /**
     * Event Log Variables
     */
    static String logFilePath = "C:\\Totem logs\\";
    static File logFile;
    static FileWriter logFileWriter;
    static BufferedWriter logBufferedWriter;
    
    /**
     * Laser Show Variables
     */
    static Process laserProcess;
    static int laserShowHour;
    static int laserShowMinute;
    static int laserShowHourDefault;
    static int laserShowMinuteDefault;
    static Boolean laserShowStarted = false;
    static Boolean laserShowRunning = false;
    
    /**
     * Environment Variables
     */
    static Boolean firstRain = false;
    static Boolean windy = false;
    static Boolean dawn = false;
    static Boolean dusk = false;
    
    /**
     * Totem Behavioural Variables
     */
    static String mood;
    static int activityLevel;
    static int startOfDay;
    static int endOfDay;
    
    /**
     * @param args the command line arguments
     * args[0]: Config file location
     */
    public static void main(String[] args) {
        ctrl = new Controlv2();
        calendar = Calendar.getInstance();
        loadConfig(args[0]); //Parse the config file
        
        /** 
         * Take note of last modified times on files to be watched
         */
        commandFileModTime = new File(commandFileLoc).lastModified();
        showsFileModTime = new File(showsFileLoc).lastModified();
        
        Date date = new Date();
        long currentTime = System.currentTimeMillis();
        int statusUpdateTimeout = 30;
        
        startLaserShow(true); // Act as if Totem is restarting after an incorrect shutdown so run sequence to turn off laser components
        
        /**
         * Totem's main loop
         */
        while(true) {
            
            // repeat loop at most once per second
            if(System.currentTimeMillis()-currentTime > 1000) {              
                
                currentTime=System.currentTimeMillis();
                calendar = Calendar.getInstance(); 
                
                if(calendar.get(Calendar.HOUR_OF_DAY) >= startOfDay && activityLevel == 0) { // If it's the start of the day then turn on
                    activityLevel=1;
                    startOfDay();
                    log("Calling startOfDay");
                } else if (calendar.get(Calendar.HOUR_OF_DAY) == endOfDay && activityLevel == 1) { // If it's the end of the day then turn off
                    // turn off
                    activityLevel=0;
                    // TODO: should empty rctrl's job queue and add a power off job
                } else if (calendar.get(Calendar.HOUR_OF_DAY) == laserShowHour && calendar.get(Calendar.MINUTE) == laserShowMinute && !laserShowStarted && suitableForLasing()) { // If it's time for the laser show and environmental conditions are suitable then start lasing
                    startLaserShow(false);
                }
                
                readCommandFile(); // Read the command file for any commands given by the GUI
                
                respondToEnvironment(); // Respond to enviromental conditions
                
                // Only update the status file every 30 seconds.
                if(statusUpdateTimeout==0) {
                    updateStatus(); 
                    statusUpdateTimeout=30;
                    log("Control is running");
                } else {
                    statusUpdateTimeout--;
                }
                
            }
        }
    }
    
    /**
     * generate random dance times until the danceTimes array is full (150) -- note sequences need a start time
     */
    private static void startOfDay() {
        Long[] danceTimes = new Long[25*6];
        Calendar tempCalendar = Calendar.getInstance();
        
        int i = 0;
        while(tempCalendar.get(Calendar.HOUR_OF_DAY) != endOfDay && i < danceTimes.length) {
            danceTimes[i] = new Long(tempCalendar.getTimeInMillis());
            tempCalendar.add(Calendar.MINUTE,20);
            randomizeTime(tempCalendar, 4);
            i++;
        }
        
        rctrl.updateDanceTimes(danceTimes); // Send the dance times to the relay controller object
        
        // Reset Environment trackers
        firstRain = false;
        dawn = false;
        dusk = false; 
        windy = false;
        
        // Reset laserShowStarted
        laserShowStarted = false;
    }
    
    /**
     * Logging functionality. Can log logContent to file and will email logs at the end of each day.
     * @param logContent 
     */
    public static void log(String logContent) {
        // If the log file doesn't exist or it's a new day
        if (logFile == null || !logFile.getName().equals(dateFormat.format(calendar.getTime()) + ".txt")) {
            try {
                // close and email as already open file
                if(logFile!=null) {
                    System.out.println("Emailing log file");
                    logBufferedWriter.close();
                    
                    // Email Geoffrey and Ruvan the log file
                    email(logFile, "ruvan@ozemail.com.au");
                    email(logFile, "geoffdb@pixent.com.au");    
                }
                
                // Create a logFile to one with todays date as the file name
                logFile = new File(logFilePath + dateFormat.format(calendar.getTime()) + ".txt");
                if (!logFile.exists()) {
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
    
    /**
     * Parse the config file once on startup
     * @param configPath 
     */
    private static void loadConfig(String configPath) {
        Properties prop = new Properties();

        try {
            // load the properties file
            FileInputStream propertiesFile = new FileInputStream(configPath);
            prop.load(propertiesFile);

            programName = prop.getProperty("ProgramName");
            statusFileLoc = prop.getProperty("statusFileLoc");
            commandFileLoc = prop.getProperty("commandFileLoc");
            showsFileLoc = prop.getProperty("showsFileLoc");
            tempShowsFileLoc = prop.getProperty("tempShowsFileLoc");
            
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
            
            // Get default laser time
            laserShowHourDefault = Integer.parseInt(prop.getProperty("defaultLaserTime").split(":")[0]);
            laserShowMinuteDefault = Integer.parseInt(prop.getProperty("defaultLaserTime").split(":")[1]);
            laserShowHour = laserShowHourDefault;
            laserShowMinute = laserShowMinuteDefault;
            log("laser show is set to start at " + Integer.toString(laserShowHour) + ":" + Integer.toString(laserShowMinute));

            // Set relay vars and create relay controller object
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
    
    /**
     * Parse the command file written to by the GUI
     */
    static void readCommandFile() {

        // Get the last modified time
        long modifiedTime = new File(commandFileLoc).lastModified();
        
        if (modifiedTime > commandFileModTime) { // if it's been modified
            commandFileModTime = modifiedTime; // record new modification time
            try {
                // parse the command file
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
    
//// TODO: This method needs looking at / making functional 
    /**
     * Read shows.txt file, update todays laser show time and remove past entries
     * Shows File entries are expected to be on seperate lines with one line format looking like
     * DD/MM/YYY,HH:MM,HH:MM,HH:MM
     * date, audience arrives, audience exits, laser show start time
     * @param force 
     */
    static void readShowsFile(Boolean force) {
        
        // Get the last modified time
        long modifiedTime = new File(showsFileLoc).lastModified();
        
        if (modifiedTime > showsFileModTime || force) { 
            showsFileModTime = modifiedTime;
 
            try {
                // load the shows file
                File shows = new File(showsFileLoc);
                BufferedReader showsReader = new BufferedReader(new FileReader(shows));
                String showLine;
                
                File tempFile = new File(tempShowsFileLoc);
                BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile));
                
                Calendar tempCalendar = Calendar.getInstance();
                Boolean todaysShowDefined = false; // Tracks whether todays show was defined otherwise show will run at default times.
                
                while ((showLine = showsReader.readLine()) != null) {
                    String[] split = showLine.split(",");
                    String[] date = split[0].split("/");
                    
                    // if current line is today
                    if(tempCalendar.get(Calendar.DATE) == Integer.parseInt(date[0]) && tempCalendar.get(Calendar.MONTH) == Integer.parseInt(date[1]) && tempCalendar.get(Calendar.YEAR) == Integer.parseInt(date[2])) {
                        
                        String[] laserTimes = split[3].split(":");
                        int proposedHour = Integer.parseInt(laserTimes[0]);
                        int proposedMinute = Integer.parseInt(laserTimes[1]);
                        
                         // TODO: Make provisions for the laserShow time to actually make the lasers not turn on or or there to be no lasers for shows -- functionality not to be implemented after talking to Geoffrey
                        // TODO: The time restrictions below should be in the interface not code.
                        if((proposedHour >= 20 && proposedMinute > 29) && (proposedHour <= 23 && proposedMinute < 31)) {
                            laserShowHour = proposedHour;
                            laserShowMinute = proposedMinute;
                            todaysShowDefined=true;
                        }
                        
//                        TODO: read the show start and end times as well as the activity level and act on it
//                        String[] showStartTimes = split[1].split(":");
//                        int 
                        
                    // Remove lines from the past
                    } else if(tempCalendar.get(Calendar.DATE) > Integer.parseInt(date[0]) && tempCalendar.get(Calendar.MONTH) >= Integer.parseInt(date[1]) && tempCalendar.get(Calendar.YEAR) >= Integer.parseInt(date[2])) {
                        continue;
                    }
                    writer.write(showLine);   
                }
                
                writer.close();
                tempFile.renameTo(shows);
                
                // Set show to default times
                if(!todaysShowDefined) {
                    laserShowHour = laserShowHourDefault;
                    laserShowMinute = laserShowMinuteDefault;
                }
                
            }catch (IOException ex) {
                ex.printStackTrace();
            }
        } 
    }
    
    /**
     * Creates a midicontroller to either run a show or the shutdown sequence 
     */
    public static void startLaserShow(Boolean justShutdown) {
        mctrl = new MIDIController(ctrl, rctrl, justShutdown);
        mctrl.start();
        laserShowStarted = !justShutdown;
    }
    
    public int getActivityLevel() {
        return activityLevel;
    }

    /**
     * Update Totem's status to file for display on the GUI
     */
    static public void updateStatus() {
        Properties status = new Properties();

        try {
            // load the status file
            FileInputStream statusFI = new FileInputStream(statusFileLoc);
            status.load(statusFI);
          
            // Update misc. fields
            status.setProperty("activityLevel", Integer.toString(activityLevel));
            status.setProperty("mood", mood);
            status.setProperty("laserShowTime", Integer.toString(laserShowHour) + ":" + Integer.toString(laserShowMinute));
            // Get the next kinetic sequence in the queue
            if(rctrl.kineticSequenceQueue.peek() == null) {
                status.setProperty("kineticSequence", "none");
            } else {
                status.setProperty("kineticSequence", rctrl.kineticSequenceQueue.peek().sequenceName);
                // TODO: Could get an iterator and iterate through the queue here to display queue elements in the UI
            }
            
            
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
            for (int i=0; i<rctrl.sensors[1].length; i++) {
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
    
    /**
     * Check environmental conditions and react appropriately
     */
    static public void respondToEnvironment() {
        /**
         * Laser Checks
         * if laser show is running and it's not suitable for lasing then end the control-midi player process
         */
        if(laserShowRunning && !suitableForLasing()) {
            laserProcess.destroy();
            laserShowRunning = false;
        }
        
        // Check that we still have power else run shutdown procedure
//        if(rctrl.sensors[0][13] < 1024) {
//            System.out.println("sensor 13 = " + Integer.toString(rctrl.sensors[0][13]));
//            System.out.println("sensor 14 = " + Integer.toString(rctrl.sensors[0][14]));
//            //Shutdown the laser show
//            if(laserShowRunning) {
//                laserProcess.destroy();
//                laserShowRunning = false;
//            }
//            // Pull in all petals and shut down
//            rctrl.kineticSequenceQueue.clear();
//            rctrl.kineticSequenceQueue.add(new KineticSequence("turnOff", false, false));
//            rctrl.executeQueue();
//            sleep(20000);
//            log("Exception: Lost power, shutting down");
//            
//            try{
//            Runtime runtime = Runtime.getRuntime();
//            Process proc = runtime.exec("shutdown -s -t 0");
//            } catch (IOException e) {}
//            System.exit(0);  
//        }
        
       /**
        * Wind Checks
        */
        // Park Totem when experiencing wind levels over 7
        if (rctrl.sensors[0][12] > 2293 && !rctrl.parked) { // If above wind level 7 
            rctrl.parked = true;
            // Pull in all petals and shut down
            rctrl.kineticSequenceQueue.clear();
            rctrl.kineticSequenceQueue.add(new KineticSequence("turnOff", false, false));
            rctrl.executeQueue();
            // TODO: Does this actually stop Totem? What about when there's a new dance or reaction?
            
        // If wind levels are below 6.8     
        } else if (rctrl.sensors[0][12] < 2210) {
            // Set parked boolean to false if below 6.8 for more then 20 minutes
            if (rctrl.parkedTime != null && System.currentTimeMillis() > rctrl.parkedTime + 1200000) {
                rctrl.parkedTime = null;
                rctrl.parked = false;
            // Set time Totem was parked and wind levels were below 6.8
            } else if (rctrl.parkedTime == null) {
                rctrl.parkedTime = System.currentTimeMillis();
            }
        // If wind levels rise above 6.8 the parked timer will reset
        } else if (rctrl.sensors[0][12] > 2211 && rctrl.parkedTime != null) {
            rctrl.parkedTime = null;
        }
        
        /**
         * Light Checks
         */
        if(!dawn && rctrl.sensors[0][7] < 254 && rctrl.sensors[1][7] == 0) {
            // TODO: add run helloSun
        }
        if(!dusk && dawn && rctrl.sensors[0][7] > 254 && rctrl.sensors[1][7] == 0) {
            // TODO: add run goodbyeSun
        }
        if (!firstRain && rctrl.sensors[0][8] > 254 && rctrl.sensors[1][8] == 0) {
            // TODO: add run rainDance
        }

    }
    
    /**
     * Checks if it's suitable for lasing
     * @return 
     */
    static public Boolean suitableForLasing() {
        // Note the " && rctrl.sensors[1][7] == 0 " clause is to ensure we've had the same sensor reading at least once
//        if(rctrl.sensors[0][7] < 254 && rctrl.sensors[1][7] == 0) { // Check night sensor 
//            log("Exception: Shutting down laser show due to ambient light levels");
//            return false;
//        } else 
            if (rctrl.sensors[0][12] > 1640) { // Check wind sensor 
            log("Exception: Shutting down laser show due to high wind levels, wind level: " + Integer.toString(rctrl.sensors[0][12]));
            return false;
        } else if (rctrl.sensors[0][8] > 254 && rctrl.sensors[1][8] == 0) {// Check rain sensor
            log("Exception: Shutting down laser show due to rain");
            return false;
        } else if(rctrl.parked){
            log("Exception: Shutting down laser show due to Totem being in parked mode");
            return false;
        }
        return true;
    }
    
    /** 
     * Sleep the tread for time milliseconds
     * @param time 
     */
    static public void sleep(int time) {
        try {
                Thread.sleep(time);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
    }
     
    /**
     * Create and send email
     * @param logFile
     * @param address 
     */
    static public void email(File logFile, String address) {
        String SMTP_HOST_NAME = "mail.drake-brockman.com.au";
        String SMTP_PORT = "587";
        final String SMTP_FROM_ADDRESS = "totem@drake-brockman.com.au";
        String SMTP_TO_ADDRESS = address;
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
