package simulador;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ponto de entrada do simulador.
 *
 * <pre>
 * java -cp out simulador.Main                          -> shell interativo (disco padrão data/disco.fs)
 * java -cp out simulador.Main --disco data/x.fs        -> shell usando outro arquivo de disco
 * java -cp out simulador.Main mkdir /docs              -> executa UM comando e encerra
 * java -cp out simulador.Main --eco &lt; script.txt       -> roda um script repetindo cada comando
 * </pre>
 */
public class Main {

    public static final String DISCO_PADRAO = "data/disco.fs";

    public static void main(String[] args) throws IOException {
        String disco = DISCO_PADRAO;
        boolean eco = false;
        List<String> comando = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--disco" -> {
                    if (i + 1 >= args.length) {
                        System.out.println("Uso: --disco <arquivo>");
                        return;
                    }
                    disco = args[++i];
                }
                case "--eco" -> eco = true;
                default -> comando.add(args[i]);
            }
        }

        System.out.println("=== Simulador de Sistema de Arquivos com Journaling ===");
        Shell shell = new Shell(disco);
        shell.setEco(eco);
        shell.montar();

        if (comando.isEmpty()) {
            shell.loop();
        } else {
            // Modo "uma chamada por execução": executa o comando e desmonta o disco.
            StringBuilder linha = new StringBuilder();
            for (String parte : comando) {
                if (linha.length() > 0) linha.append(' ');
                linha.append(parte.contains(" ") ? "\"" + parte + "\"" : parte);
            }
            shell.executarLinha(linha.toString());
            shell.checkpoint();
        }
    }
}
