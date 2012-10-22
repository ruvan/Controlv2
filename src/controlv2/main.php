<?php

session_start();
if (!isset($_SESSION['authenticated']) || $_SESSION['authenticated'] != true) {
    header('Location: index.php');
} else {
    echo "<!DOCTYPE html><html><body>";
}

// Rewrite the config file with given values
if (isset($_POST['Config-Form'])) {
    $config = fopen("config.properties", "w") or exit("Unable to open config file!");
    fwrite($config, date("#D M d H:i:s T Y"));
    foreach ($_POST as $key => $value) {
        if ($key != "Config-Form") {
            fwrite($config, "\n");
            fwrite($config, "$key=$value");
        }
    }
}

// Parse relay form and send to command.txt
if (isset($_POST['Relay-Form'])) {
    $relayString = "";
    foreach ($_POST as $relayNumber => $relayArray) {
        if (is_array($relayArray)) {
            $relayValue = 0;
            foreach ($relayArray as $relayValueElement) {
                $relayValue+=$relayValueElement;
            }
            $relayString.= "$relayNumber-$relayValue,";
        }
    }
    $relayString = "r=" . substr_replace($relayString,"",-1);
    send_command($relayString);
}

// Read and display config file
if ($_SESSION['userlevel'] == 1) {
    $config = fopen("config.properties", "r") or exit("Unable to open config file!");
    $lastModTime = explode(" ", fgets($config));
    echo "<form action='main.php' method='post'> <br> \n";
    echo "<table border='1'>\n";
    while (!feof($config)) {
        $line = explode("=", fgets($config));
        echo "<tr> <td> $line[0] </td> <td> <input type='text' name='$line[0]' value='$line[1]' /> </td> </tr> \n";
    }
    echo "<tr> <td> Last mod: $lastModTime[1] $lastModTime[2] $lastModTime[3]</td> <td> <input type='submit' name='Config-Form' value='Submit' /> </td>  </tr> \n </table> </form>";
    fclose($config);
}

// Read and display status file
if ($_SESSION['userlevel'] == 1) {
//    
//    $relayTableStarted=false;
//    $relayTableFinished=false;
//    $sensorTableStarted=false;
//    $sensorTableFinished=false;
//    $miscTableStarted=false;
//    
    // Build relay table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<form action='main.php' method='post'>\n <table border='1'> \n ";
                    echo "<tr><td>Bank #</td><td>Bank Status</td></tr>\n";
    while (!feof($status)) {
        $line = fgets($status);
        if (substr_compare($line, "#", 0, 1) != 0) { // Have a property
            
            $property = explode("=", $line);
            
            if (substr_compare($property[0], "b", 0, 1) == 0) { // Have got a relay bank
                
                $bankNumber = intval(substr($property[0], 2));
                $bankValue = substr_replace($property[1],"",-1); // remove the newline character
                echo "<tr><td>$bankNumber</td><td>";
                $bankValue = str_split($bankValue);
                $sizeOf_bankValue = sizeof($bankValue);
                for ($i=0; $i<$sizeOf_bankValue; $i++) { // For those relays we do know the value of
                    if (strcmp($bankValue[$i], "1") == 0) {
                        echo "<input type=\"checkbox\" checked=\"yes\" value='" . pow(2,$i) . "' name=\"$bankNumber" . "[]\">  ";
                    } else {
                        echo "<input type=\"checkbox\" value='" . pow(2,$i) . "' name=\"$bankNumber" ."[]\">  ";
                    }
                }
                for ($i=0; $i<8-$sizeOf_bankValue; $i++) {
                    echo "<input type=\"checkbox\" value='" . pow(2,$i+$sizeOf_bankValue) . "' name=\"$bankNumber" ."[]\">  ";
                }
                echo "</td></tr>\n";
                
            }
        }
    } // End of Status file
    fclose($status);
    echo "<tr><td>Manual override</td><td><input name='Relay-Form' type='submit' value='Set'></td></tr> \n </table> </form> \n";
    
    // Build sensor table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<table border=\"1\"> \n";
                    echo "<tr><td>Sensor type</td><td>Sensor Status</td></tr>\n";
    while (!feof($status)) {
        $line = fgets($status);
        if (substr_compare($line, "#", 0, 1) != 0) { // Have a property
            
            $property = explode("=", $line);
            if (substr_compare($property[0], "s", 0, 1) == 0)  { // Have got a sensor value
                
              
                $sensorType = substr($property[0], 2, 1);
                if(strcmp($sensorType, "m") == 0) { // have a motion sensor
                    echo "<tr><td>Motion " . substr($property[0], 4, 1) . "</td><td>$property[1]</td></tr> \n";
                } elseif (strcmp($sensorType, "w") == 0) { // Have a wind sensor
                    echo "<tr><td>Wind " . substr($property[0], 4, 1) . "</td><td>$property[1]</td></tr> \n";
                } elseif (strcmp($sensorType, "r") == 0) { // Have a rain sensor
                    echo "<tr><td>Rain</td><td>$property[1]</td></tr> \n";
                } elseif (strcmp($sensorType, "l") == 0) { // Have a light sensor
                    echo "<tr><td>Light</td><td>$property[1]</td></tr> \n";
                }
                
            } 
        } 
        } // end of status file
        fclose($status);
        echo "</table> \n";
        
        // Build other status table
        $status = fopen("status.properties", "r") or exit("Unable to open config file!");
        echo "<table border=\"1\"> \n";
                    echo "<tr><td>Status Type</td><td>Status</td></tr>\n";
        while (!feof($status)) {
        $line = fgets($status);
        if (substr_compare($line, "#", 0, 1) != 0) { // Have a property
            $property = explode("=", $line);
        if (substr_compare($property[0], "s", 0, 1) != 0 && substr_compare($property[0], "b", 0, 1) != 0) { // Have got a status other than relay or sensor related
  
                echo "<tr><td>$property[0]</td><td>$property[1]</td></tr> \n";
            }
        }
        } //end of status file
        echo "</table> \n";
        fclose($status);
    
//    // Build relay table
//    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
//    while (!feof($status)) {
//        $line = fgets($status);
//        if (substr_compare($line, "#", 0, 1) == 0) { // Have a comment
//            //echo $line . "<br>";
//        } else { // Have a property
//            $property = explode("=", $line);
//            
//            if (substr_compare($property[0], "b", 0, 1) == 0) { // Have got a relay bank
//                if(!$relayTableStarted) {
//                    echo "<form action='main.php' method='post'>\n <table border='1'> \n ";
//                    echo "<tr><td>Bank #</td><td>Bank Status</td></tr>\n";
//                    $relayTableStarted=true;
//                }
//                $bankNumber = intval(substr($property[0], 2));
//                $bankValue = substr_replace($property[1],"",-1); // remove the newline character
//                echo "<tr><td>$bankNumber</td><td>";
//                $bankValue = str_split($bankValue);
//                $sizeOf_bankValue = sizeof($bankValue);
//                for ($i=0; $i<$sizeOf_bankValue; $i++) { // For those relays we do know the value of
//                    if (strcmp($bankValue[$i], "1") == 0) {
//                        echo "<input type=\"checkbox\" checked=\"yes\" value='" . pow(2,$i) . "' name=\"$bankNumber" . "[]\">  ";
//                    } else {
//                        echo "<input type=\"checkbox\" value='" . pow(2,$i) . "' name=\"$bankNumber" ."[]\">  ";
//                    }
//                }
//                for ($i=0; $i<8-$sizeOf_bankValue; $i++) {
//                    echo "<input type=\"checkbox\" value='" . pow(2,$i+$sizeOf_bankValue) . "' name=\"$bankNumber" ."[]\">  ";
//                }
//                echo "</td></tr>\n";
//                
//            } elseif (substr_compare($property[0], "s", 0, 1) == 0)  { // Have got a sensor value
//                if($relayTableStarted && !$relayTableFinished) {
//                    echo "<tr><td>Manual override</td><td><input name='Relay-Form' type='submit' value='Set'></td></tr> \n </table> </form> \n";
//                    $relayTableFinished=true;
//                }
//                if(!$sensorTableStarted) {
//                    echo "<table border=\"1\"> \n";
//                    echo "<tr><td>Sensor type</td><td>Sensor Status</td></tr>\n";
//                    $sensorTableStarted=true;
//                }
//                $sensorType = substr($property[0], 2, 1);
//                if(strcmp($sensorType, "m") == 0) { // have a motion sensor
//                    echo "<tr><td>Motion " . substr($property[0], 4, 1) . "</td><td>$property[1]</td></tr> \n";
//                } elseif (strcmp($sensorType, "w") == 0) { // Have a wind sensor
//                    echo "<tr><td>Wind " . substr($property[0], 4, 1) . "</td><td>$property[1]</td></tr> \n";
//                } elseif (strcmp($sensorType, "r") == 0) { // Have a rain sensor
//                    echo "<tr><td>Rain</td><td>$property[1]</td></tr> \n";
//                } elseif (strcmp($sensorType, "l") == 0) { // Have a light sensor
//                    echo "<tr><td>Light</td><td>$property[1]</td></tr> \n";
//                }
//                
//            } else { // Have got a status other than relay or sensor related
//                if($sensorTableStarted && !$sensorTableFinished) {
//                    echo "</table> \n";
//                    $relayTableFinished=true;
//                }
//                if(!$miscTableStarted) {
//                    echo "<table border=\"1\"> \n";
//                    echo "<tr><td>Status Type</td><td>Status</td></tr>\n";
//                    $miscTableStarted=true;
//                }
//                echo "<tr><td>$property[0]</td><td>$property[1]</td></tr> \n";
//            }
//            
//        }
//    } // End of Status file
    
    
    // Finish misc table
//    echo "</table> \n";
//    fclose($status);
}


echo "</body></html>";

function send_command($command) {
    $commandFile = fopen("command.txt", "a") or exit("Unable to open conmmand file!");
    fwrite($commandFile, $command);
    fclose($commandFile);
    sleep(2);
}

?>