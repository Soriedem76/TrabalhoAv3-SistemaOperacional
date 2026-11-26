@echo off
rem Compila e executa o simulador. Argumentos sao repassados para a Main.
rem   executar.bat                 -> shell interativo
rem   executar.bat ls /            -> executa um unico comando
chcp 65001 > nul
if not exist out mkdir out
javac -encoding UTF-8 -d out src\simulador\*.java || exit /b 1
java -Dstdout.encoding=UTF-8 -cp out simulador.Main %*
