@echo off
ECHO =======================================
ECHO  Compiling Your STUFFFF
ECHO =======================================
javac --module-path "C:\jfx\lib;lib\*" --add-modules javafx.controls,javafx.graphics -d bin src/module-info.java src/com/traffic/interfaces/*.java src/com/traffic/server/*.java src/com/traffic/client/*.java

ECHO --------------------------------------------------------
ECHO Successfully compiled your STUFFFF
ECHO --------------------------------------------------------
pause