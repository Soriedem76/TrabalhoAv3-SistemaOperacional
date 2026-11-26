package simulador;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/** Representa um arquivo: nome, conteúdo textual e metadados. */
public class File extends Node {
    private static final long serialVersionUID = 1L;

    private String conteudo;

    public File(String nome, String conteudo) {
        super(nome);
        this.conteudo = conteudo == null ? "" : conteudo;
    }

    public String getConteudo() { return conteudo; }

    public void setConteudo(String conteudo) {
        this.conteudo = conteudo == null ? "" : conteudo;
        this.modificadoEm = LocalDateTime.now();
    }

    /** Tamanho em bytes (UTF-8). */
    @Override
    public long getTamanho() {
        return conteudo.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public boolean isDiretorio() { return false; }

    /** Cria uma cópia independente (mesmo conteúdo, sem pai). */
    public File copiar() {
        return new File(nome, conteudo);
    }
}
