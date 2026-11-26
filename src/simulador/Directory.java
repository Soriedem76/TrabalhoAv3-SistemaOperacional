package simulador;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Representa um diretório: guarda seus filhos (arquivos e subdiretórios) ordenados por nome. */
public class Directory extends Node {
    private static final long serialVersionUID = 1L;

    private final Map<String, Node> filhos = new TreeMap<>();

    public Directory(String nome) {
        super(nome);
    }

    public boolean contem(String nome) { return filhos.containsKey(nome); }

    public Node buscar(String nome) { return filhos.get(nome); }

    public boolean estaVazio() { return filhos.isEmpty(); }

    public List<Node> listarFilhos() { return new ArrayList<>(filhos.values()); }

    void adicionar(Node no) {
        if (filhos.containsKey(no.getNome())) {
            throw new FsException("Já existe '" + no.getNome() + "' em " + getCaminho());
        }
        filhos.put(no.getNome(), no);
        no.setPai(this);
        this.modificadoEm = LocalDateTime.now();
    }

    void remover(String nome) {
        Node removido = filhos.remove(nome);
        if (removido != null) {
            removido.setPai(null);
            this.modificadoEm = LocalDateTime.now();
        }
    }

    void renomearFilho(String antigo, String novo) {
        Node no = filhos.remove(antigo);
        if (no == null) throw new FsException("'" + antigo + "' não existe em " + getCaminho());
        no.setNome(novo);
        filhos.put(novo, no);
        this.modificadoEm = LocalDateTime.now();
    }

    /** Soma recursiva do tamanho de tudo que está dentro do diretório. */
    @Override
    public long getTamanho() {
        long total = 0;
        for (Node n : filhos.values()) total += n.getTamanho();
        return total;
    }

    @Override
    public boolean isDiretorio() { return true; }
}
