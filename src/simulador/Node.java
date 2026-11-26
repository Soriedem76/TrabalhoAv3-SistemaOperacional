package simulador;

import java.io.Serializable;
import java.time.LocalDateTime;

/** Nó genérico da árvore do sistema de arquivos (base de File e Directory). */
public abstract class Node implements Serializable {
    private static final long serialVersionUID = 1L;

    protected String nome;
    protected Directory pai;
    protected final LocalDateTime criadoEm;
    protected LocalDateTime modificadoEm;

    protected Node(String nome) {
        this.nome = nome;
        this.criadoEm = LocalDateTime.now();
        this.modificadoEm = this.criadoEm;
    }

    public String getNome() { return nome; }
    public Directory getPai() { return pai; }
    public LocalDateTime getCriadoEm() { return criadoEm; }
    public LocalDateTime getModificadoEm() { return modificadoEm; }

    void setNome(String nome) {
        this.nome = nome;
        this.modificadoEm = LocalDateTime.now();
    }

    void setPai(Directory pai) { this.pai = pai; }

    /** Caminho absoluto do nó, ex.: /docs/trabalho.txt (a raiz é "/"). */
    public String getCaminho() {
        if (pai == null) return "/";
        String caminhoPai = pai.getCaminho();
        return caminhoPai.equals("/") ? "/" + nome : caminhoPai + "/" + nome;
    }

    public abstract long getTamanho();

    public abstract boolean isDiretorio();
}
