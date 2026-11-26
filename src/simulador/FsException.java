package simulador;

/** Erro de operação do sistema de arquivos simulado (nome duplicado, caminho inexistente etc.). */
public class FsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FsException(String mensagem) {
        super(mensagem);
    }
}
