<?php

    session_start();

if(isset($_SESSION['authenticated']) && $_SESSION['authenticated']) {
    header('Location: main.php');
}

if(isset($_POST['password'])) {
    if($_POST['password']=='password1') {
        $_SESSION['userlevel']=1;
        $_SESSION['authenticated']=true;
        header('Location: main.php');
    } elseif($_POST['password']=='password2') {
        $_SESSION['userlevel']=2;
        $_SESSION['authenticated']=true;
        header('Location: main.php');
    } else {
        echo "<b>Incorrect Password</b>";
    }
}

?>

<!DOCTYPE html>
<html>
    <head>
    <style type='text/css'>
         body {background: #eee url('resources/Totem-V2.png') no-repeat top center ;}
 
        .login {
          height: 24px;
          width: 275px;
          background: #E3B817;
          position: absolute;
          padding: 10px;

          /*Centering Method 2*/
          margin: -50px 0 0 -50px;
          left: 70%;
          top: 50%;
        }

    </style>
    </head>
    
    <body>
        <div class='login'>
        <form action='index.php' method='post'>
        Password: <input type='password' name='password' />
        <input type="submit" value="Login" />
        </form>
        </div>
    </body>
</html>