<?php

session_start();
if (!isset($_SESSION['authenticated']) || $_SESSION['authenticated'] != true) {
    header('Location: index.php');
} else {
    echo "
        <!DOCTYPE html><html><body>
        <style type='text/css'>
        #statusDiv {
            position:absolute;
            top:5%;
            left:60%;
        }
        #sensorDiv {
            position:absolute;
            top:5%;
            left:40%;
        }
        #relayDiv {
            position:absolute;
            top:25%;
            left:60%;
        }
        </style>
        ";
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
    $relayString = "r,o=" . substr_replace($relayString,"",-1);
    send_command($relayString);
}

// Add a sequence 
if (isset($_POST['Sequence-Form'])) {
    $relayString = "r,a=" . $_POST['sequence'];
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

    
    // Build relay table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<div id='relayDiv'><form action='main.php' method='post'>\n <table border='1'> \n ";
                    echo "<tr><td>Bank #</td><td>Bank Status</td></tr>\n";
    while (!feof($status)) {
        $line = fgets($status); 
        if(strlen($line)==0) { break; } // to ignore the trailing empty line
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
    echo "<tr><td>Manual override</td><td><input name='Relay-Form' type='submit' value='Set'></td></tr> \n </table> </form> </div> \n";
    
    // Build sensor table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<div id='sensorDiv'><table border=\"1\"> \n";
                    echo "<tr><td>Sensor type</td><td>Sensor Status</td></tr>\n";
    while (!feof($status)) {
        $line = fgets($status);
        if(strlen($line)==0) { break; } // to ignore the trailing empty line
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
        echo "</table> </div> \n";
        
        // Build other status table
        $status = fopen("status.properties", "r") or exit("Unable to open config file!");
        echo "<div id='statusDiv'><table border=\"1\"> \n";
                    echo "<tr><td>Status Type</td><td>Status</td></tr>\n";
        while (!feof($status)) {
        $line = fgets($status);
        if(strlen($line)==0) { break; } // to ignore the trailing empty line
        if (substr_compare($line, "#", 0, 1) != 0) { // Have a property
            $property = explode("=", $line);
        if (substr_compare($property[0], "s", 0, 1) != 0 && substr_compare($property[0], "b", 0, 1) != 0) { // Have got a status other than relay or sensor related
  
                echo "<tr><td>$property[0]</td><td>$property[1]</td></tr> \n";
            }
        }
        } //end of status file
        
        // build sequence select form
        echo "<tr>
            <form action='main.php' method='post'> \n 
            <td>
            <select name='sequence'>
            <option value='runDiagonalChase'>Diagonal Chase</option>
            <option value='runAllOnOff'>All On Off</option>
            </select>
            </td>
            <td>
            <input name='Sequence-Form' type='submit' value='submit'>
            </td>
            </form>
            </tr>
            </table> </div> \n";
        fclose($status);

}


echo "</body></html>";

// Function currently clears the comand file and writes one comand to line one.
function send_command($command) {
    $commandFile = fopen("command.txt", "w") or exit("Unable to open conmmand file!");
    fwrite($commandFile, $command);
    fclose($commandFile);
    sleep(2);
}

?>