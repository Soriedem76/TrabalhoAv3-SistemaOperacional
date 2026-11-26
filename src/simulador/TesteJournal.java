package simulador;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Testes de execução do journaling, incluindo falhas simuladas no meio das operações.
 * Não usa bibliotecas externas: execute com  java -cp out simulador.TesteJournal
 *
 * "Queda" = jogar fora o objeto em memória sem salvar e montar o disco de novo a partir
 * dos arquivos (imagem .fs + .journal), exatamente como aconteceria após reiniciar a máquina.
 */
public class TesteJournal {

    private static final Path PASTA = Paths.get("data", "testes");
    private static int passou = 0, falhou = 0;

    /** Disco montado: sistema + journal + relatório da recuperação. */
    private record Disco(FileSystemSimulator fs, Journal journal, Journal.Recuperacao rec) { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(PASTA);

        teste("1. Operações normais geram BEGIN + COMMIT (write-ahead)", TesteJournal::operacoesNormais);
        teste("2. Queda ANTES do COMMIT: operação pendente é descartada", TesteJournal::quedaAntesDoCommit);
        teste("3. Queda DEPOIS do COMMIT (sem checkpoint): operações são reaplicadas", TesteJournal::quedaDepoisDoCommit);
        teste("4. Queda no meio do checkpoint: nada é reaplicado duas vezes", TesteJournal::quedaNoCheckpoint);
        teste("5. Linha do log cortada (escrita incompleta) é ignorada pelo CRC", TesteJournal::linhaIncompleta);
        teste("6. Conteúdo com '|', '\\' e quebra de linha sobrevive ao replay", TesteJournal::caracteresEspeciais);

        System.out.println();
        System.out.println("Resultado: " + passou + " passou, " + falhou + " falhou.");
        if (falhou > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // Casos de teste
    // ------------------------------------------------------------------

    private static void operacoesNormais() throws IOException {
        Disco d = montarNovo("t1");
        d.fs().criarDiretorio("/docs");
        d.fs().criarArquivo("/docs/a.txt", "abc");
        d.fs().copiarArquivo("/docs/a.txt", "/docs/b.txt");
        d.fs().renomearArquivo("/docs/b.txt", "c.txt");

        List<EntradaJournal> log = d.journal().lerEntradas();
        afirmar(log.size() == 4, "deveria haver 4 entradas, há " + log.size());
        afirmar(log.stream().allMatch(e -> e.getStatus() == EntradaJournal.Status.CONFIRMADA),
                "todas deveriam estar CONFIRMADAS");
        afirmar(log.get(0).getTipo().equals("MKDIR") && log.get(3).getTipo().equals("RENAME_FILE"),
                "tipos registrados na ordem errada");
        afirmar(d.fs().getUltimoIdAplicado() == log.get(3).getId(), "ultimoIdAplicado incorreto");
        log.forEach(e -> System.out.println("     " + e));
    }

    private static void quedaAntesDoCommit() throws IOException {
        Disco d = montarNovo("t2");
        d.fs().criarDiretorio("/ok");
        d.journal().simularFalhaNaProximaOperacao();
        try {
            d.fs().criarDiretorio("/interrompido");
            afirmar(false, "a falha simulada não aconteceu");
        } catch (Journal.FalhaSimulada esperado) {
            System.out.println("     " + esperado.getMessage());
        }
        // Na memória o diretório chegou a ser criado, mas a memória se perde na queda.
        afirmar(d.fs().getRaiz().contem("interrompido"), "a operação deveria ter rodado em memória");

        Disco depois = montar("t2");
        System.out.println("     reaplicadas=" + depois.rec().reaplicadas() + ", descartadas=" + depois.rec().descartadas());
        afirmar(depois.fs().getRaiz().contem("ok"), "/ok (confirmada) deveria ter sido reaplicada");
        afirmar(!depois.fs().getRaiz().contem("interrompido"), "/interrompido (sem COMMIT) deveria ser descartada");
        afirmar(depois.rec().reaplicadas() == 1 && depois.rec().descartadas() == 1, "contagem da recuperação errada");

        // Os IDs continuam crescendo depois da recuperação.
        long antes = depois.fs().getUltimoIdAplicado();
        depois.fs().criarDiretorio("/novo");
        afirmar(depois.fs().getUltimoIdAplicado() > antes, "o ID de uma nova operação deveria ser maior");
    }

    private static void quedaDepoisDoCommit() throws IOException {
        Disco d = montarNovo("t3");
        FileSystemSimulator fs = d.fs();
        fs.criarDiretorio("/projeto");
        fs.criarDiretorio("/projeto/src");
        fs.criarArquivo("/projeto/leia.txt", "README");
        fs.copiarArquivo("/projeto/leia.txt", "/projeto/src");
        fs.renomearDiretorio("/projeto/src", "codigo");
        fs.apagarArquivo("/projeto/leia.txt");
        String esperado = arvore(fs);
        // Queda: nenhum checkpoint foi feito, a imagem em disco ainda está vazia.

        Disco depois = montar("t3");
        System.out.println("     reaplicadas=" + depois.rec().reaplicadas());
        afirmar(depois.rec().reaplicadas() == 6, "deveria reaplicar 6 operações");
        afirmar(arvore(depois.fs()).equals(esperado),
                "árvore recuperada diferente:\n" + arvore(depois.fs()) + "\nesperado:\n" + esperado);
    }

    private static void quedaNoCheckpoint() throws IOException {
        Disco d = montarNovo("t4");
        d.fs().criarDiretorio("/x");
        d.fs().criarArquivo("/x/y.txt", "dado");
        // Checkpoint interrompido: a imagem foi gravada, mas o journal não chegou a ser esvaziado.
        d.fs().salvar(imagem("t4"));

        Disco depois = montar("t4");
        System.out.println("     jaNoCheckpoint=" + depois.rec().jaNoCheckpoint() + ", reaplicadas=" + depois.rec().reaplicadas());
        afirmar(depois.rec().jaNoCheckpoint() == 2 && depois.rec().reaplicadas() == 0,
                "as 2 operações já estavam na imagem e não podiam ser reaplicadas");
        afirmar(depois.fs().listar("/x").size() == 1, "/x deveria ter exatamente 1 arquivo");
    }

    private static void linhaIncompleta() throws IOException {
        Disco d = montarNovo("t5");
        d.fs().criarDiretorio("/a");
        // Simula a energia acabando enquanto uma linha era escrita no log.
        Files.writeString(Paths.get(imagem("t5") + ".journal"), "BEGIN|2|2026-11-26T10:00|MKDIR|/b",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Disco depois = montar("t5");
        System.out.println("     linhasInvalidas=" + depois.rec().linhasInvalidas() + ", reaplicadas=" + depois.rec().reaplicadas());
        afirmar(depois.rec().linhasInvalidas() == 1, "a linha cortada deveria ser detectada");
        afirmar(depois.fs().getRaiz().contem("a") && !depois.fs().getRaiz().contem("b"), "estado recuperado errado");
    }

    private static void caracteresEspeciais() throws IOException {
        Disco d = montarNovo("t6");
        String texto = "linha 1\nlinha 2 | com pipe \\ e barra";
        d.fs().criarArquivo("/especial.txt", texto);

        Disco depois = montar("t6");
        File f = (File) depois.fs().obter("/especial.txt");
        afirmar(f.getConteudo().equals(texto), "conteúdo alterado no replay: " + f.getConteudo());
    }

    // ------------------------------------------------------------------
    // Infraestrutura
    // ------------------------------------------------------------------

    private static String imagem(String nome) {
        return PASTA.resolve(nome + ".fs").toString();
    }

    /** Apaga os arquivos do teste e monta um disco vazio. */
    private static Disco montarNovo(String nome) throws IOException {
        Files.deleteIfExists(Paths.get(imagem(nome)));
        Files.deleteIfExists(Paths.get(imagem(nome) + ".journal"));
        FileSystemSimulator.criarNovo(imagem(nome));
        return montar(nome);
    }

    /** Monta o disco como o Shell faz ao iniciar: carrega a imagem e recupera pelo journal. */
    private static Disco montar(String nome) {
        FileSystemSimulator fs = FileSystemSimulator.carregar(imagem(nome));
        Journal journal = new Journal(imagem(nome) + ".journal");
        fs.setJournal(journal);
        Journal.Recuperacao rec = journal.recuperar(fs);
        return new Disco(fs, journal, rec);
    }

    private static String arvore(FileSystemSimulator fs) {
        StringBuilder sb = new StringBuilder();
        desenhar(fs.getRaiz(), sb);
        return sb.toString();
    }

    private static void desenhar(Node n, StringBuilder sb) {
        sb.append(n.getCaminho()).append(n.isDiretorio() ? "/" : " = " + ((File) n).getConteudo()).append('\n');
        if (n instanceof Directory d) d.listarFilhos().forEach(f -> desenhar(f, sb));
    }

    private interface Caso { void rodar() throws Exception; }

    private static void teste(String nome, Caso caso) {
        System.out.println("-> " + nome);
        try {
            caso.rodar();
            passou++;
            System.out.println("   PASSOU");
        } catch (Throwable t) {
            falhou++;
            System.out.println("   FALHOU: " + t.getMessage());
        }
    }

    private static void afirmar(boolean condicao, String mensagem) {
        if (!condicao) throw new AssertionError(mensagem);
    }
}
