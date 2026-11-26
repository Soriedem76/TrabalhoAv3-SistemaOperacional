package simulador;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Simulador do sistema de arquivos. Os caminhos são sempre absolutos a partir da raiz "/"
 * (ex.: /docs/notas.txt). Aceita "." e ".." na normalização.
 *
 * Cada operação que altera o sistema valida tudo primeiro e só então chama o Journal
 * (registrar -> executar -> confirmar), integrando o journaling (classe Journal).
 */
public class FileSystemSimulator implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Directory raiz = new Directory("");
    private transient Journal journal;

    /**
     * ID da última operação do journal já aplicada a este sistema. É salvo junto com a imagem,
     * então a recuperação sabe quais entradas do log já estão no checkpoint (evita reaplicar 2x).
     */
    private long ultimoIdAplicado = 0;

    public FileSystemSimulator() { }

    public void setJournal(Journal journal) { this.journal = journal; }

    public Journal getJournal() { return journal; }

    public long getUltimoIdAplicado() { return ultimoIdAplicado; }

    void setUltimoIdAplicado(long id) { this.ultimoIdAplicado = id; }

    public Directory getRaiz() { return raiz; }

    /** Devolve o nó (arquivo ou diretório) de um caminho absoluto. */
    public Node obter(String caminho) {
        return resolver(partes(caminho));
    }

    // ------------------------------------------------------------------
    // Operações de diretório
    // ------------------------------------------------------------------

    public void criarDiretorio(String caminho) {
        List<String> p = partes(caminho);
        if (p.isEmpty()) throw new FsException("A raiz já existe.");
        String nome = p.get(p.size() - 1);
        validarNome(nome);
        Directory pai = diretorioPai(p);
        if (pai.contem(nome)) throw new FsException("Já existe: " + juntar(p));
        executar("MKDIR", () -> pai.adicionar(new Directory(nome)), caminho);
    }

    public void apagarDiretorio(String caminho) {
        apagarDiretorio(caminho, false);
    }

    /** Se recursivo for false, só apaga diretório vazio. */
    public void apagarDiretorio(String caminho, boolean recursivo) {
        Node no = resolver(partes(caminho));
        if (!(no instanceof Directory dir)) throw new FsException("Não é um diretório: " + caminho);
        if (dir == raiz) throw new FsException("Não é possível apagar a raiz.");
        if (!recursivo && !dir.estaVazio()) {
            throw new FsException("Diretório não está vazio: " + dir.getCaminho() + " (use o modo recursivo).");
        }
        Directory pai = dir.getPai();
        executar(recursivo ? "RMDIR_R" : "RMDIR", () -> pai.remover(dir.getNome()), caminho);
    }

    public void renomearDiretorio(String caminho, String novoNome) {
        Node no = resolver(partes(caminho));
        if (!(no instanceof Directory dir)) throw new FsException("Não é um diretório: " + caminho);
        if (dir == raiz) throw new FsException("Não é possível renomear a raiz.");
        renomearNo(dir, novoNome, "RENAME_DIR", caminho);
    }

    /** Lista o conteúdo de um diretório, uma linha por item. */
    public List<String> listar(String caminho) {
        Node no = resolver(partes(caminho));
        if (!(no instanceof Directory dir)) throw new FsException("Não é um diretório: " + caminho);
        List<String> linhas = new ArrayList<>();
        for (Node n : dir.listarFilhos()) {
            if (n.isDiretorio()) {
                linhas.add("[DIR] " + n.getNome());
            } else {
                linhas.add("[ARQ] " + n.getNome() + " (" + n.getTamanho() + " bytes)");
            }
        }
        return linhas;
    }

    // ------------------------------------------------------------------
    // Operações de arquivo
    // ------------------------------------------------------------------

    /** Cria um arquivo com conteúdo (necessário para ter o que copiar/apagar/renomear). */
    public void criarArquivo(String caminho, String conteudo) {
        List<String> p = partes(caminho);
        if (p.isEmpty()) throw new FsException("Caminho inválido para arquivo.");
        String nome = p.get(p.size() - 1);
        validarNome(nome);
        Directory pai = diretorioPai(p);
        if (pai.contem(nome)) throw new FsException("Já existe: " + juntar(p));
        executar("CREATE_FILE", () -> pai.adicionar(new File(nome, conteudo)), caminho, conteudo);
    }

    /**
     * Copia um arquivo. Se o destino for um diretório existente, a cópia mantém o nome original;
     * caso contrário, o último trecho do destino é o nome da cópia. Não sobrescreve.
     */
    public void copiarArquivo(String origem, String destino) {
        Node src = resolver(partes(origem));
        if (!(src instanceof File arq)) throw new FsException("A origem não é um arquivo: " + origem);

        List<String> pd = partes(destino);
        Node existente = tentarResolver(pd);
        Directory dirDestino;
        String nomeCopia;
        if (existente instanceof Directory d) {
            dirDestino = d;
            nomeCopia = arq.getNome();
        } else if (existente != null) {
            throw new FsException("Já existe um arquivo em: " + juntar(pd));
        } else {
            nomeCopia = pd.get(pd.size() - 1);
            validarNome(nomeCopia);
            dirDestino = diretorioPai(pd);
        }
        if (dirDestino.contem(nomeCopia)) {
            throw new FsException("Já existe '" + nomeCopia + "' em " + dirDestino.getCaminho());
        }
        Directory alvo = dirDestino;
        String nome = nomeCopia;
        executar("COPY", () -> {
            File copia = arq.copiar();
            copia.setNome(nome);
            alvo.adicionar(copia);
        }, origem, destino);
    }

    public void apagarArquivo(String caminho) {
        Node no = resolver(partes(caminho));
        if (!(no instanceof File arq)) throw new FsException("Não é um arquivo: " + caminho);
        Directory pai = arq.getPai();
        executar("DELETE_FILE", () -> pai.remover(arq.getNome()), caminho);
    }

    public void renomearArquivo(String caminho, String novoNome) {
        Node no = resolver(partes(caminho));
        if (!(no instanceof File arq)) throw new FsException("Não é um arquivo: " + caminho);
        renomearNo(arq, novoNome, "RENAME_FILE", caminho);
    }

    // ------------------------------------------------------------------
    // Persistência (arquivo que simula o sistema de arquivos)
    // ------------------------------------------------------------------

    /** Grava todo o sistema de arquivos em um arquivo do disco (serialização Java). */
    public void salvar(String caminhoArquivo) {
        Path destino = Paths.get(caminhoArquivo);
        try {
            if (destino.getParent() != null) Files.createDirectories(destino.getParent());
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(destino))) {
                out.writeObject(this);
            }
        } catch (IOException e) {
            throw new FsException("Erro ao salvar '" + caminhoArquivo + "': " + e.getMessage());
        }
    }

    /** Carrega um sistema de arquivos previamente salvo. */
    public static FileSystemSimulator carregar(String caminhoArquivo) {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(Paths.get(caminhoArquivo)))) {
            return (FileSystemSimulator) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new FsException("Erro ao carregar '" + caminhoArquivo + "': " + e.getMessage());
        }
    }

    /** Cria um sistema vazio e já grava o arquivo no disco. */
    public static FileSystemSimulator criarNovo(String caminhoArquivo) {
        FileSystemSimulator fs = new FileSystemSimulator();
        fs.salvar(caminhoArquivo);
        return fs;
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    private void renomearNo(Node no, String novoNome, String tipo, String caminho) {
        validarNome(novoNome);
        Directory pai = no.getPai();
        String antigo = no.getNome();
        if (antigo.equals(novoNome)) throw new FsException("O novo nome é igual ao atual.");
        if (pai.contem(novoNome)) throw new FsException("Já existe '" + novoNome + "' em " + pai.getCaminho());
        executar(tipo, () -> pai.renomearFilho(antigo, novoNome), caminho, novoNome);
    }

    /**
     * Fluxo do journaling (write-ahead logging):
     * registrar (BEGIN no disco) -> executar em memória -> confirmar (COMMIT no disco).
     * Se a execução lançar erro, a entrada é marcada como ABORT. Sem Journal plugado
     * (ex.: durante o replay da recuperação), a operação apenas executa.
     */
    private void executar(String tipo, Runnable acao, String... parametros) {
        if (journal == null) {
            acao.run();
            return;
        }
        long id = journal.registrar(tipo, parametros);
        try {
            acao.run();
        } catch (RuntimeException e) {
            journal.abortar(id);
            throw e;
        }
        journal.confirmar(id);
        ultimoIdAplicado = id;
    }

    private void validarNome(String nome) {
        if (nome == null || nome.isBlank()) throw new FsException("Nome vazio.");
        if (nome.contains("/")) throw new FsException("Nome não pode conter '/': " + nome);
        if (nome.equals(".") || nome.equals("..")) throw new FsException("Nome inválido: " + nome);
    }

    private List<String> partes(String caminho) {
        if (caminho == null || caminho.isBlank()) throw new FsException("Caminho vazio.");
        LinkedList<String> r = new LinkedList<>();
        for (String p : caminho.trim().split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!r.isEmpty()) r.removeLast();
                continue;
            }
            r.add(p);
        }
        return r;
    }

    private String juntar(List<String> partes) {
        return "/" + String.join("/", partes);
    }

    private Node resolver(List<String> partes) {
        Node atual = raiz;
        for (String p : partes) {
            if (!(atual instanceof Directory d)) {
                throw new FsException("'" + atual.getCaminho() + "' não é um diretório.");
            }
            Node prox = d.buscar(p);
            if (prox == null) throw new FsException("Não encontrado: " + juntar(partes));
            atual = prox;
        }
        return atual;
    }

    private Node tentarResolver(List<String> partes) {
        try {
            return resolver(partes);
        } catch (FsException e) {
            return null;
        }
    }

    private Directory diretorioPai(List<String> partes) {
        Node n = resolver(partes.subList(0, partes.size() - 1));
        if (!(n instanceof Directory d)) throw new FsException("'" + n.getCaminho() + "' não é um diretório.");
        return d;
    }
}
