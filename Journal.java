package simulador;

/**
 * PLACEHOLDER: esta classe será substituída pela implementação real da Pessoa B.
 * Serve apenas para o núcleo (Pessoa A) compilar e testar. A Pessoa B deve manter
 * as assinaturas de registrar(...) e confirmar(...).
 */
public class Journal {

    /** Registra a operação ANTES de executá-la (write-ahead). Retorna o id da entrada. */
    public long registrar(String tipo, String... parametros) {
        return -1;
    }

    /** Marca a operação como concluída (commit). */
    public void confirmar(long id) {
    }
}
