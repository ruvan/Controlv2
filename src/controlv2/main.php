<?php

session_start();
if (!isset($_SESSION['authenticated']) || $_SESSION['authenticated'] != true) {
    header('Location: index.php');
}

?>

<!DOCTYPE html><html><body>
    <style type='text/css'>
        body {background: #EEE;}
        table {border-collapse:collapse}
/*        input {
            width: 115px;
        }*/
        .center
            {
            margin-left:auto;
            margin-right:auto;
            width:797px;
            background-color:#F3F781;
            padding:40px;
            }
        .right
            {
            position:relative;
            float:right;
            top:5px;
            right:1%;
            background-color:#F3F781;
            }
        .left
            {
            position:relative;
            float:left;
            top:5px;
            right:-1%;
            background-color:#F3F781;
            }
        #config 
            {
            position:relative;
            top: 270px;
            left:-190px;
            }
        #relay
            {
            position:relative;
            top:-220px;
            }
    </style>
    <SCRIPT language="javascript">
        function addShow() {
            form = document.forms["Schedule-Form"];
            numLines = parseInt(form.elements["numLines"].value);
            numLines++;

            var table=document.getElementById("Schedule-Table");
            var row=table.insertRow(numLines);
            var dateCell=row.insertCell(0);
            var arrivalCell=row.insertCell(1);
            var departureCell=row.insertCell(2);
            var laserCell=row.insertCell(3);
            var nameCell=row.insertCell(4);
            var removeCell=row.insertCell(5);
           
            dateCell.innerHTML="<input type='text' name='date"+numLines+"' maxlength='10' size='15'>";
            arrivalCell.innerHTML="<input type='text' name='arrival"+numLines+"' maxlength='5' size='15'>";
            departureCell.innerHTML="<input type='text' name='departure"+numLines+"' maxlength='5' size='15'>";
            laserCell.innerHTML="<input type='text' name='laser"+numLines+"' maxlength='5' size='15'>";
            nameCell.innerHTML="<input type='text' name='name"+numLines+"' maxlength='15' size='15'>";
            removeCell.innerHTML="<input type='image' src='resources/remove.png' name='remove"+numLines+"' onclick='removeRow("+numLines+")' width='30' height='30'>";
            // Set new numLines value
            form.elements["numLines"].value=numLines;
        }
        
        function removeRow(rowNum) {
            rowNumber=parseInt(rowNum);
            form = document.forms["Schedule-Form"];
            numLines = parseInt(form.elements["numLines"].value);
            document.getElementById("Schedule-Table").deleteRow(rowNumber);
            
            while(rowNumber!=numLines){
                oldNumber = rowNumber;
                rowNumber++;
                document.getElementsByName("remove"+rowNumber)[0].setAttribute('onclick', 'removeRow('+ oldNumber +')');
                document.getElementsByName("remove"+rowNumber)[0].setAttribute('name', 'remove'+oldNumber);
                document.getElementsByName("date"+rowNumber)[0].setAttribute('name', 'date'+oldNumber);
                document.getElementsByName("arrival"+rowNumber)[0].setAttribute('name', 'arrival'+oldNumber);
                document.getElementsByName("departure"+rowNumber)[0].setAttribute('name', 'departure'+oldNumber);
                document.getElementsByName("laser"+rowNumber)[0].setAttribute('name', 'laser'+oldNumber);
                document.getElementsByName("name"+rowNumber)[0].setAttribute('name', 'name'+oldNumber);   
            }
            
            // Set new numLines value
            form.elements["numLines"].value=numLines-1;
            // These lines were causing the following line to be saved to the shows file ",,,,"
            // if(numLines==1){ 
            //    addShow();
            //}
        }
        
        function validateForm() {
            numLines=parseInt(document.forms["Schedule-Form"]["numLines"].value);
            for (var i=0; i<numLines; i++) {
                if (checkdate(document.forms["Schedule-Form"]["date"+i].value) == false) {
                    alert("Detected incorrectly formatted date");
                    return false;
                }
            }
//            var date=document.forms["Schedule-Form"]["fname"].value;
//            if (x==null || x=="")
//              {
//              alert("First name must be filled out");
//              return false;
//              }
        }
        
        function checkdate(input){
            var validformat=/^\d{2}\/\d{2}\/\d{4}$/ //Basic check for format validity
            var returnval=false
            if (!validformat.test(input.value))
            alert("Invalid Date Format. Please correct and submit again.")
            else{ //Detailed check for valid date ranges
            var monthfield=input.value.split("/")[0]
            var dayfield=input.value.split("/")[1]
            var yearfield=input.value.split("/")[2]
            var dayobj = new Date(yearfield, monthfield-1, dayfield)
            if ((dayobj.getMonth()+1!=monthfield)||(dayobj.getDate()!=dayfield)||(dayobj.getFullYear()!=yearfield))
            alert("Invalid Day, Month, or Year range detected. Please correct and submit again.")
            else
            returnval=true
            }
            if (returnval==false) input.select()
            return returnval
        }
    </SCRIPT>

<?php
//// Begin POST submission handling section
 
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

// Respond to posted schedule
if (isset($_POST['numLines'])) {
    write_schedule($_POST);
}

//// End POST submission handling section


echo "<div id='main' name='main' class='center'>";

//// Begin display section for normal user

// Build activity scheduling table
    $schedule = fopen("shows.txt", "r") or exit("Unable to open show scheduling file!");
    echo "<div id='schedule'><form action='main.php' method='post' id='Schedule-Form' name='Schedule-Form'>\n <table id='Schedule-Table' border='1'> \n ";
    echo "<tr><td><b>Show Date</b><pre>(dd/mm/yyyy)</pre></td><td><b>Arrival Time</b><pre>(HH:MM)</pre></td><td><b>Departure Time</b><pre>(HH:MM)</pre></td><td><b>Laser Show Time</b><pre>(HH:MM)</pre></td><td><b>Show Name</b><pre>alphanumeric</pre></td></tr>\n";
    $numLines = 0; // Will hold the number of schedule lines which were returned     
    while (!feof($schedule)) {
        $line = fgets($schedule);
        if(!empty($line)){
            $numLines++;
            list($date, $arrival, $departure, $laser, $name) = explode(",", $line);
            echo "<tr><td><input type='text' value='$date' maxlength='10' size='15' name='date$numLines'></td><td><input type='text' value='$arrival' maxlength='5' size='15' name='arrival$numLines'></td><td><input type='text' value='$departure' maxlength='5' size='15' name='departure$numLines'></td><td><input type='text' value='$laser' maxlength='5' size='15' name='laser$numLines'></td><td><input type='text' value='$name' maxlength='15' size='15' name='name$numLines'></td><td><input type='image' src='resources/remove.png' name='remove$numLines' title='Remove Show' onclick='removeRow($numLines)' width='30' height='30'></td></tr> \n ";
        }
    }
    echo "<tr><td><input type='hidden' value='$numLines' name='numLines' id='numLines'></td><td></td><td></td><td></td><td></td><td><input type='image' name='add' src='resources/add.png' title='Add Show' onclick='addShow();return false;' width='30' height='30'/><input type='image' value='Save' name='Schedule-Form-Save' title='Save' onclick='return validateForm()' src='resources/save.png' width='30' height='30'/></td></tr>\n";
    echo "</table></form></div>\n";
    fclose($schedule);

// Build status table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<div id='status' class='left'><table border=\"1\"> \n";
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
    fclose($status);
    if ($_SESSION['userlevel'] == 2) {
    // build sequence select form
    echo "<tr>
        <form action='main.php' method='post'> \n 
        <td>
        <select name='sequence'>
        <option value='runDiagonalChase'>Diagonal Chase</option>
        <option value='runAllOnOff'>All On Off</option>
        <option value='runInputTest'>Input Test</option>
        </select>
        </td>
        <td>
        <input name='Sequence-Form' type='submit' value='submit'>
        </td>
        </form>
        </tr>";
    }
    echo "</table> </div> \n";

// Build sensor table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<div id='sensor' class='right'><table border=\"1\"> \n";
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


// Temp holder for dance times etc
echo "<div id='temp' name='temp'></div>";









// Read and display status file
if ($_SESSION['userlevel'] == 2) {

    echo "<div id='config' class='left'>";
    // Read and display config file
    $config = fopen("config.properties", "r") or exit("Unable to open config file!");
    $lastModTime = explode(" ", fgets($config));
    echo "<form action='main.php' method='post'> <br> \n";
    echo "<table border='1'>\n";
    while (!feof($config)) {
        $line = explode("=", fgets($config));
        echo "<tr> <td> $line[0] </td> <td> <input type='text' name='$line[0]' value='$line[1]' /> </td> </tr> \n";
    }
    echo "<tr> <td> Last mod: $lastModTime[1] $lastModTime[2] $lastModTime[3]</td> <td> <input type='submit' name='Config-Form' value='Submit' /> </td>  </tr> \n </table> </form> </div>";
    fclose($config);
    
    
    // Build relay table
    $status = fopen("status.properties", "r") or exit("Unable to open config file!");
    echo "<div id='relay' class='right'><form action='main.php' method='post'>\n <table border='1'> \n ";
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
      
        
}


echo "</div></body></html>";

// Function currently clears the comand file and writes one command to line one.
function send_command($command) {
    $commandFile = fopen("command.txt", "w") or exit("Unable to open conmmand file!");
    fwrite($commandFile, $command);
    fclose($commandFile);
    sleep(2);
}

// Function takes schedule POST args and writes the shows.txt file
function write_schedule($POST) {
    $schedule = fopen("shows.txt", "w") or exit("Unable to open show scheduling file!");
    for($i=1; $i <= $POST['numLines']; $i++) {
        fwrite($schedule, $POST['date'.$i] . "," . $POST['arrival'.$i] . "," . $POST['departure'.$i] . "," . $POST['laser'.$i] . "," . $POST['name'.$i] . "\n");
    }
    fclose($schedule);
}

?>