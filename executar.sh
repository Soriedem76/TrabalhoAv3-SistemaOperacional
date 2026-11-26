#!/usr/bin/env sh
# Compila e executa o simulador. Argumentos sao repassados para a Main.
#   ./executar.sh                -> shell interativo
#   ./executar.sh ls /           -> executa um unico comando
set -e
mkdir -p out
javac -encoding UTF-8 -d out src/simulador/*.java
java -Dstdout.encoding=UTF-8 -cp out simulador.Main "$@"
