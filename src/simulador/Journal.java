package simulador;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Journal (log de operações) com <b>write-ahead logging</b> e recuperação por <b>redo</b>.
 *
 * <h2>Formato do arquivo de log</h2>
 * Arquivo texto, somente de acréscimo (append-only), uma linha por registro:
 * <pre>
 * BEGIN|id|timestamp|TIPO|param1|param2|#crc
 * COMMIT|id|timestamp|#crc
 * ABORT|id|timestamp|#crc
 * </pre>
 * O campo {@code #crc} é um CRC32 da linha; uma linha cortada no meio (queda durante a escrita)
 * ou corrompida não confere e é ignorada na recuperação.
 *
 * <h2>Protocolo</h2>
 * <ol>
 *   <li>{@link #registrar}: grava BEGIN no disco (com sync) <i>antes</i> de alterar o sistema.</li>
 *   <li>O {@link FileSystemSimulator} aplica a alteração na árvore em memória.</li>
 *   <li>{@link #confirmar}: grava COMMIT no disco (com sync).</li>
 *   <li>{@link #checkpoint}: grava a imagem do sistema (arquivo .fs) e esvazia o log.</li>
 * </ol>
 *
 * <h2>Recuperação ({@link #recuperar})</h2>
 * Ao iniciar, a imagem do último checkpoint é carregada e o log é lido:
 * operações CONFIRMADAS posteriores ao checkpoint são <b>reaplicadas</b>; operações PENDENTES
 * (sem COMMIT) são <b>descartadas</b>, pois nunca chegaram à imagem em disco.
 */
public final class Journal {

    /** Erro lançado de propósito para simular uma queda do sistema (falta de energia, kill -9...). */
    public static class FalhaSimulada extends Error {
        private static final long serialVersionUID = 1L;

        public FalhaSimulada(String mensagem) {
            super(mensagem);
        }
    }

    /** Resultado de uma recuperação, para exibir ao usuário. */
    public record Recuperacao(int reaplicadas, int descartadas, int jaNoCheckpoint,
                              int abortadas, int linhasInvalidas, List<String> detalhes) {
        public boolean houveAlgo() {
            return reaplicadas + descartadas + jaNoCheckpoint + abortadas + linhasInvalidas > 0;
        }
    }

    private static final String SEP = "|";

    private final Path arquivoLog;
    private long proximoId;
    private boolean falharAntesDoProximoCommit = false;

    /** Abre (ou cria) o journal no caminho indicado. */
    public Journal(String caminhoLog) {
        this.arquivoLog = Paths.get(caminhoLog);
        try {
            if (arquivoLog.getParent() != null) Files.createDirectories(arquivoLog.getParent());
            if (!Files.exists(arquivoLog)) Files.createFile(arquivoLog);
        } catch (IOException e) {
            throw new FsException("Não foi possível abrir o journal '" + caminhoLog + "': " + e.getMessage());
        }
        long maior = 0;
        for (EntradaJournal e : lerEntradas()) maior = Math.max(maior, e.getId());
        this.proximoId = maior + 1;
    }

    public Path getArquivoLog() { return arquivoLog; }

    // ------------------------------------------------------------------
    // API usada pelo FileSystemSimulator (assinaturas combinadas com a Pessoa A)
    // ------------------------------------------------------------------

    /** Registra a operação ANTES de executá-la (write-ahead). Retorna o id da entrada. */
    public long registrar(String tipo, String... parametros) {
        long id = proximoId++;
        List<String> campos = new ArrayList<>();
        campos.add("BEGIN");
        campos.add(Long.toString(id));
        campos.add(LocalDateTime.now().toString());
        campos.add(tipo);
        for (String p : parametros) campos.add(p == null ? "" : p);
        gravarLinha(campos);
        return id;
    }

    /** Marca a operação como concluída (commit). */
    public void confirmar(long id) {
        if (falharAntesDoProximoCommit) {
            falharAntesDoProximoCommit = false;
            throw new FalhaSimulada("Queda simulada após executar a operação #" + id + " e antes do COMMIT.");
        }
        gravarLinha(List.of("COMMIT", Long.toString(id), LocalDateTime.now().toString()));
    }

    /** Marca a operação como cancelada (a execução lançou erro). */
    public void abortar(long id) {
        gravarLinha(List.of("ABORT", Long.toString(id), LocalDateTime.now().toString()));
    }

    // ------------------------------------------------------------------
    // Checkpoint e recuperação
    // ------------------------------------------------------------------

    /**
     * Grava a imagem completa do sistema de arquivos e esvazia o log.
     * A imagem é escrita em um arquivo temporário e depois movida, para que uma queda
     * no meio da gravação não destrua a imagem anterior.
     */
    public void checkpoint(FileSystemSimulator fs, String caminhoImagem) {
        Path imagem = Paths.get(caminhoImagem);
        Path temporario = Paths.get(caminhoImagem + ".tmp");
        fs.salvar(temporario.toString());
        try {
            substituir(temporario, imagem);
            // Só depois que a imagem está segura no disco o log pode ser esvaziado.
            // Se cair antes disso, o ultimoIdAplicado salvo na imagem evita reaplicar em dobro.
            Files.write(arquivoLog, new byte[0], StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new FsException("Falha no checkpoint (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    /**
     * Move o temporário por cima da imagem. No Windows, antivírus/OneDrive podem segurar o arquivo
     * por alguns milissegundos, então tentamos algumas vezes antes de desistir.
     */
    private static void substituir(Path origem, Path destino) throws IOException {
        IOException ultimoErro = null;
        for (int tentativa = 0; tentativa < 10; tentativa++) {
            try {
                try {
                    Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) { // inclui AccessDeniedException (arquivo em uso)
                ultimoErro = e;
                try {
                    Thread.sleep(50L * (tentativa + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw ultimoErro;
    }

    /**
     * Recupera o sistema após uma queda: reaplica as operações confirmadas que ainda não estão
     * na imagem e descarta as pendentes. Durante o replay o journal fica desplugado para que as
     * operações reaplicadas não sejam registradas de novo.
     */
    public Recuperacao recuperar(FileSystemSimulator fs) {
        int reaplicadas = 0, descartadas = 0, jaNoCheckpoint = 0, abortadas = 0;
        List<String> detalhes = new ArrayList<>();
        Leitura leitura = ler();

        fs.setJournal(null);
        try {
            for (EntradaJournal e : leitura.entradas()) {
                if (e.getId() <= fs.getUltimoIdAplicado()) {
                    jaNoCheckpoint++;
                    detalhes.add("ignorada (já está na imagem): " + e);
                    continue;
                }
                switch (e.getStatus()) {
                    case CONFIRMADA -> {
                        try {
                            reaplicar(fs, e);
                            fs.setUltimoIdAplicado(e.getId());
                            reaplicadas++;
                            detalhes.add("REAPLICADA: " + e);
                        } catch (FsException ex) {
                            detalhes.add("erro ao reaplicar " + e + " -> " + ex.getMessage());
                        }
                    }
                    case PENDENTE -> {
                        descartadas++;
                        detalhes.add("DESCARTADA (sem COMMIT): " + e);
                    }
                    case ABORTADA -> abortadas++;
                }
            }
        } finally {
            fs.setJournal(this);
        }
        proximoId = Math.max(proximoId, fs.getUltimoIdAplicado() + 1);
        if (leitura.linhasInvalidas() > 0) {
            detalhes.add(leitura.linhasInvalidas() + " linha(s) corrompida(s)/incompleta(s) ignorada(s).");
        }
        return new Recuperacao(reaplicadas, descartadas, jaNoCheckpoint, abortadas,
                leitura.linhasInvalidas(), detalhes);
    }

    /** Executa novamente uma operação confirmada usando os métodos públicos do simulador. */
    private void reaplicar(FileSystemSimulator fs, EntradaJournal e) {
        switch (e.getTipo()) {
            case "MKDIR" -> fs.criarDiretorio(e.param(0));
            case "RMDIR" -> fs.apagarDiretorio(e.param(0), false);
            case "RMDIR_R" -> fs.apagarDiretorio(e.param(0), true);
            case "RENAME_DIR" -> fs.renomearDiretorio(e.param(0), e.param(1));
            case "CREATE_FILE" -> fs.criarArquivo(e.param(0), e.param(1));
            case "COPY" -> fs.copiarArquivo(e.param(0), e.param(1));
            case "DELETE_FILE" -> fs.apagarArquivo(e.param(0));
            case "RENAME_FILE" -> fs.renomearArquivo(e.param(0), e.param(1));
            default -> throw new FsException("Tipo de operação desconhecido no journal: " + e.getTipo());
        }
    }

    // ------------------------------------------------------------------
    // Simulação de falhas e consulta
    // ------------------------------------------------------------------

    /** A próxima operação será executada em memória, mas o sistema "cai" antes do COMMIT. */
    public void simularFalhaNaProximaOperacao() {
        falharAntesDoProximoCommit = true;
    }

    /** Desarma uma falha simulada que não chegou a acontecer. */
    public void cancelarSimulacao() {
        falharAntesDoProximoCommit = false;
    }

    /** Todas as entradas válidas do log, em ordem de id, com o status atual de cada uma. */
    public List<EntradaJournal> lerEntradas() {
        return ler().entradas();
    }

    // ------------------------------------------------------------------
    // Leitura e escrita do arquivo de log
    // ------------------------------------------------------------------

    private record Leitura(List<EntradaJournal> entradas, int linhasInvalidas) { }

    private Leitura ler() {
        List<String> linhas;
        try {
            linhas = Files.readAllLines(arquivoLog, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FsException("Erro ao ler o journal: " + e.getMessage());
        }
        Map<Long, EntradaJournal> porId = new LinkedHashMap<>();
        int invalidas = 0;
        for (String linha : linhas) {
            if (linha.isBlank()) continue;
            List<String> campos = decodificarLinha(linha);
            if (campos == null || campos.size() < 3) {
                invalidas++;
                continue;
            }
            try {
                long id = Long.parseLong(campos.get(1));
                switch (campos.get(0)) {
                    case "BEGIN" -> porId.put(id, new EntradaJournal(id, campos.get(3),
                            campos.subList(4, campos.size()), LocalDateTime.parse(campos.get(2))));
                    case "COMMIT" -> marcar(porId, id, EntradaJournal.Status.CONFIRMADA);
                    case "ABORT" -> marcar(porId, id, EntradaJournal.Status.ABORTADA);
                    default -> invalidas++;
                }
            } catch (RuntimeException ex) {
                invalidas++;
            }
        }
        List<EntradaJournal> entradas = new ArrayList<>(porId.values());
        entradas.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return new Leitura(entradas, invalidas);
    }

    private void marcar(Map<Long, EntradaJournal> porId, long id, EntradaJournal.Status status) {
        EntradaJournal e = porId.get(id);
        if (e != null) e.setStatus(status);
    }

    /** Acrescenta uma linha ao log e força a gravação no disco (SYNC) antes de retornar. */
    private void gravarLinha(List<String> campos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < campos.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(escapar(campos.get(i)));
        }
        String corpo = sb.toString();
        String linha = corpo + SEP + "#" + crc(corpo) + System.lineSeparator();
        try {
            Files.writeString(arquivoLog, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new FsException("Erro ao gravar no journal: " + e.getMessage());
        }
    }

    /** Devolve os campos da linha, ou null se o CRC não conferir (linha incompleta/corrompida). */
    private static List<String> decodificarLinha(String linha) {
        int pos = linha.lastIndexOf(SEP + "#");
        if (pos < 0) return null;
        String corpo = linha.substring(0, pos);
        String crcLido = linha.substring(pos + 2).trim();
        if (!crc(corpo).equals(crcLido)) return null;
        List<String> campos = new ArrayList<>();
        for (String c : corpo.split("\\|", -1)) campos.add(desescapar(c));
        return campos;
    }

    private static String crc(String texto) {
        CRC32 crc = new CRC32();
        crc.update(texto.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    /** Escapa \, | e quebras de linha para que cada registro ocupe exatamente uma linha. */
    private static String escapar(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\p");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String desescapar(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) {
                    case 'p' -> '|';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
