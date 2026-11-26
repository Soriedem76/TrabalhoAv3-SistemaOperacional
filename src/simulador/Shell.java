package simulador;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Shell interativo (modo avançado). Interpreta comandos no estilo Unix e os traduz em chamadas
 * de método do {@link FileSystemSimulator}. Todas as alterações passam pelo {@link Journal}.
 */
public class Shell {

    /** A cada quantas operações confirmadas é feito um checkpoint automático. */
    public static final int OPERACOES_POR_CHECKPOINT = 5;

    private final String caminhoImagem;
    private FileSystemSimulator fs;
    private Journal journal;
    private String dirAtual = "/";
    private int operacoesDesdeCheckpoint = 0;
    private boolean eco = false;

    public Shell(String caminhoImagem) {
        this.caminhoImagem = caminhoImagem;
    }

    /** Se true, repete cada comando lido (útil quando a entrada vem de um script/pipe). */
    public void setEco(boolean eco) { this.eco = eco; }

    public FileSystemSimulator getFs() { return fs; }

    // ------------------------------------------------------------------
    // Inicialização: carrega a imagem, recupera pelo journal e faz checkpoint
    // ------------------------------------------------------------------

    public void montar() {
        if (Files.exists(Paths.get(caminhoImagem))) {
            fs = FileSystemSimulator.carregar(caminhoImagem);
            System.out.println("Disco carregado: " + caminhoImagem);
        } else {
            fs = FileSystemSimulator.criarNovo(caminhoImagem);
            System.out.println("Disco novo criado: " + caminhoImagem);
        }
        journal = new Journal(caminhoImagem + ".journal");
        fs.setJournal(journal);

        Journal.Recuperacao r = journal.recuperar(fs);
        if (r.houveAlgo()) {
            System.out.println("== Recuperação pelo journal ==");
            r.detalhes().forEach(d -> System.out.println("  " + d));
            System.out.printf("  Resumo: %d reaplicada(s), %d descartada(s), %d já no checkpoint, "
                            + "%d abortada(s), %d linha(s) inválida(s).%n",
                    r.reaplicadas(), r.descartadas(), r.jaNoCheckpoint(), r.abortadas(), r.linhasInvalidas());
        } else {
            System.out.println("Journal limpo: o disco foi desmontado corretamente.");
        }
        checkpoint();
    }

    public void checkpoint() {
        journal.checkpoint(fs, caminhoImagem);
        operacoesDesdeCheckpoint = 0;
    }

    // ------------------------------------------------------------------
    // Laço principal
    // ------------------------------------------------------------------

    public void loop() throws IOException {
        System.out.println("Digite 'help' para ver os comandos.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("fs:" + dirAtual + "$ ");
            System.out.flush();
            String linha = in.readLine();
            if (linha == null) break;          // fim da entrada (Ctrl+D / fim do script)
            linha = linha.replace("﻿", ""); // BOM que o PowerShell coloca ao usar pipe
            if (eco) System.out.println(linha);
            if (!executarLinha(linha)) break;  // "exit"
        }
        try {
            checkpoint();
            System.out.println("Checkpoint feito. Disco desmontado com segurança.");
        } catch (FsException e) {
            System.out.println("Erro: " + e.getMessage());
            System.out.println("As operações continuam seguras no journal e serão recuperadas na próxima execução.");
        }
    }

    /** Executa uma linha de comando. Retorna false quando o usuário pede para sair. */
    public boolean executarLinha(String linha) {
        List<String> args = tokenizar(linha);
        if (args.isEmpty()) return true;
        String cmd = args.get(0).toLowerCase();
        List<String> a = args.subList(1, args.size());
        try {
            switch (cmd) {
                case "exit", "sair", "quit" -> { return false; }
                case "help", "ajuda" -> ajuda();
                case "crash" -> crash(a);
                case "kill" -> kill();
                default -> executarComando(cmd, a);
            }
        } catch (FsException e) {
            System.out.println("Erro: " + e.getMessage());
        } catch (Journal.FalhaSimulada e) {
            System.out.println("*** SISTEMA CAIU: " + e.getMessage());
            System.out.println("*** O processo será encerrado SEM checkpoint. Inicie o simulador de novo para ver a recuperação.");
            Runtime.getRuntime().halt(1);
        }
        return true;
    }

    private void executarComando(String cmd, List<String> a) {
        switch (cmd) {
            case "ls" -> ls(a.isEmpty() ? dirAtual : abs(a.get(0)));
            case "cd" -> cd(a.isEmpty() ? "/" : abs(a.get(0)));
            case "pwd" -> System.out.println(dirAtual);
            case "tree" -> tree(a.isEmpty() ? dirAtual : abs(a.get(0)));
            case "cat" -> cat(abs(arg(a, 0, "cat <arquivo>")));
            case "mkdir" -> alterou(() -> fs.criarDiretorio(abs(arg(a, 0, "mkdir <diretorio>"))));
            case "rmdir" -> rmdir(a);
            case "touch" -> touch(a);
            case "cp" -> alterou(() -> fs.copiarArquivo(abs(arg(a, 0, "cp <origem> <destino>")),
                    abs(arg(a, 1, "cp <origem> <destino>"))));
            case "rm" -> alterou(() -> fs.apagarArquivo(abs(arg(a, 0, "rm <arquivo>"))));
            case "mv" -> mv(a);
            case "journal" -> mostrarJournal();
            case "checkpoint", "sync" -> {
                // (se falhar, o FsException é exibido e o journal continua intacto)
                checkpoint();
                System.out.println("Checkpoint feito: imagem gravada e journal esvaziado.");
            }
            default -> System.out.println("Comando desconhecido: " + cmd + " (digite 'help').");
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    private void ls(String caminho) {
        List<String> itens = fs.listar(caminho);
        if (itens.isEmpty()) System.out.println("(vazio)");
        itens.forEach(System.out::println);
    }

    private void cd(String caminho) {
        if (!fs.obter(caminho).isDiretorio()) throw new FsException("Não é um diretório: " + caminho);
        dirAtual = caminho;
    }

    private void tree(String caminho) {
        Node no = fs.obter(caminho);
        System.out.println(no.getCaminho());
        if (no instanceof Directory d) imprimirArvore(d, "");
    }

    private void imprimirArvore(Directory d, String prefixo) {
        List<Node> filhos = d.listarFilhos();
        for (int i = 0; i < filhos.size(); i++) {
            Node n = filhos.get(i);
            boolean ultimo = i == filhos.size() - 1;
            System.out.println(prefixo + (ultimo ? "`-- " : "|-- ") + n.getNome()
                    + (n.isDiretorio() ? "/" : " (" + n.getTamanho() + " bytes)"));
            if (n instanceof Directory sub) imprimirArvore(sub, prefixo + (ultimo ? "    " : "|   "));
        }
    }

    private void cat(String caminho) {
        if (!(fs.obter(caminho) instanceof File f)) throw new FsException("Não é um arquivo: " + caminho);
        System.out.println(f.getConteudo());
    }

    private void rmdir(List<String> a) {
        boolean recursivo = !a.isEmpty() && a.get(0).equals("-r");
        List<String> resto = recursivo ? a.subList(1, a.size()) : a;
        String caminho = abs(arg(resto, 0, "rmdir [-r] <diretorio>"));
        if (dentroDe(dirAtual, caminho)) throw new FsException("Saia do diretório antes de apagá-lo.");
        alterou(() -> fs.apagarDiretorio(caminho, recursivo));
    }

    private void touch(List<String> a) {
        String caminho = abs(arg(a, 0, "touch <arquivo> [conteúdo]"));
        String conteudo = String.join(" ", a.subList(1, a.size()));
        alterou(() -> fs.criarArquivo(caminho, conteudo));
    }

    /** mv renomeia (arquivo ou diretório) dentro do mesmo diretório. */
    private void mv(List<String> a) {
        String uso = "mv <caminho> <novo-nome>";
        String origem = abs(arg(a, 0, uso));
        String novo = arg(a, 1, uso);
        if (novo.contains("/")) {
            String destino = abs(novo);
            if (!pai(destino).equals(pai(origem))) {
                throw new FsException("mv só renomeia dentro do mesmo diretório (use cp + rm para mover).");
            }
            novo = destino.substring(destino.lastIndexOf('/') + 1);
        }
        String novoNome = novo;
        Node no = fs.obter(origem);
        if (no.isDiretorio()) {
            if (dentroDe(dirAtual, origem)) throw new FsException("Saia do diretório antes de renomeá-lo.");
            alterou(() -> fs.renomearDiretorio(origem, novoNome));
        } else {
            alterou(() -> fs.renomearArquivo(origem, novoNome));
        }
    }

    private void mostrarJournal() {
        List<EntradaJournal> entradas = journal.lerEntradas();
        System.out.println("Journal: " + journal.getArquivoLog() + " (" + entradas.size()
                + " entrada(s) desde o último checkpoint)");
        if (entradas.isEmpty()) return;
        System.out.printf("%-5s %-19s %-11s %-11s %s%n", "ID", "TIMESTAMP", "TIPO", "STATUS", "PARÂMETROS");
        entradas.forEach(System.out::println);
    }

    /** crash <comando>: executa o comando e derruba o sistema antes do COMMIT. */
    private void crash(List<String> a) {
        if (a.isEmpty()) throw new FsException("Uso: crash <comando que altera o sistema>");
        journal.simularFalhaNaProximaOperacao();
        System.out.println("[falha armada: o sistema vai cair depois de executar e antes do COMMIT]");
        try {
            executarComando(a.get(0).toLowerCase(), a.subList(1, a.size()));
            System.out.println("[o comando não altera o sistema; falha desarmada]");
        } finally {
            // Se o comando não chegou ao COMMIT (erro de validação, ls...), desarma a falha.
            journal.cancelarSimulacao();
        }
    }

    /** kill: encerra imediatamente sem checkpoint (as operações já confirmadas ficam só no journal). */
    private void kill() {
        System.out.println("*** Queda simulada (kill): encerrando sem checkpoint. "
                + operacoesDesdeCheckpoint + " operação(ões) confirmada(s) estão apenas no journal.");
        Runtime.getRuntime().halt(1);
    }

    private void ajuda() {
        System.out.println("""
                Comandos (caminhos podem ser absolutos ou relativos ao diretório atual):
                  ls [dir]                  lista o conteúdo de um diretório
                  cd <dir> | pwd            muda / mostra o diretório atual
                  tree [dir]                mostra a árvore de diretórios
                  mkdir <dir>               cria diretório
                  rmdir [-r] <dir>          apaga diretório (vazio, ou tudo com -r)
                  touch <arq> [texto]       cria arquivo com o conteúdo informado
                  cat <arq>                 mostra o conteúdo de um arquivo
                  cp <origem> <destino>     copia arquivo (destino pode ser um diretório)
                  rm <arq>                  apaga arquivo
                  mv <caminho> <novo-nome>  renomeia arquivo ou diretório
                  journal                   mostra o log de operações
                  checkpoint                grava a imagem do disco e esvazia o journal
                  crash <comando>           executa o comando e simula queda antes do COMMIT
                  kill                      simula queda sem checkpoint (após os COMMITs)
                  exit                      faz checkpoint e sai""");
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    /** Executa uma alteração, informa sucesso e faz checkpoint automático a cada N operações. */
    private void alterou(Runnable operacao) {
        operacao.run();
        System.out.println("ok");
        if (++operacoesDesdeCheckpoint >= OPERACOES_POR_CHECKPOINT) {
            checkpoint();
            System.out.println("[checkpoint automático após " + OPERACOES_POR_CHECKPOINT + " operações]");
        }
    }

    private static String arg(List<String> a, int i, String uso) {
        if (i >= a.size()) throw new FsException("Uso: " + uso);
        return a.get(i);
    }

    /** Converte para caminho absoluto normalizado (resolve relativo, "." e ".."). */
    private String abs(String caminho) {
        String bruto = caminho.startsWith("/") ? caminho : dirAtual + "/" + caminho;
        LinkedList<String> partes = new LinkedList<>();
        for (String p : bruto.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!partes.isEmpty()) partes.removeLast();
            } else {
                partes.add(p);
            }
        }
        return "/" + String.join("/", partes);
    }

    private static String pai(String caminhoAbs) {
        int i = caminhoAbs.lastIndexOf('/');
        return i <= 0 ? "/" : caminhoAbs.substring(0, i);
    }

    /** true se 'caminho' é igual a 'dir' ou está dentro dele. */
    private static boolean dentroDe(String caminho, String dir) {
        return caminho.equals(dir) || caminho.startsWith(dir + "/");
    }

    /** Separa a linha em argumentos, respeitando "aspas" para textos com espaço. */
    static List<String> tokenizar(String linha) {
        List<String> tokens = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean aspas = false, temToken = false;
        for (char c : linha.toCharArray()) {
            if (c == '"') {
                aspas = !aspas;
                temToken = true;
            } else if (Character.isWhitespace(c) && !aspas) {
                if (temToken) tokens.add(atual.toString());
                atual.setLength(0);
                temToken = false;
            } else {
                atual.append(c);
                temToken = true;
            }
        }
        if (temToken) tokens.add(atual.toString());
        return tokens;
    }
}
