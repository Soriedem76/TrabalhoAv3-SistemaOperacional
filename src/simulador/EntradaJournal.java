package simulador;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Uma operação registrada no journal: ID, tipo, parâmetros, status e timestamp. */
public class EntradaJournal {

    /** Ciclo de vida de uma operação no log. */
    public enum Status {
        /** Registrada (BEGIN) mas ainda sem COMMIT: a operação pode ter sido interrompida. */
        PENDENTE,
        /** Registrada e confirmada (COMMIT): pode ser reaplicada com segurança. */
        CONFIRMADA,
        /** Registrada e cancelada (ABORT): a execução falhou e nada deve ser reaplicado. */
        ABORTADA
    }

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final long id;
    private final String tipo;
    private final List<String> parametros;
    private final LocalDateTime timestamp;
    private Status status;

    public EntradaJournal(long id, String tipo, List<String> parametros, LocalDateTime timestamp) {
        this.id = id;
        this.tipo = tipo;
        this.parametros = List.copyOf(parametros);
        this.timestamp = timestamp;
        this.status = Status.PENDENTE;
    }

    public long getId() { return id; }
    public String getTipo() { return tipo; }
    public List<String> getParametros() { return parametros; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Status getStatus() { return status; }

    void setStatus(Status status) { this.status = status; }

    /** Parâmetro na posição i (ou "" se não existir). */
    public String param(int i) {
        return i < parametros.size() ? parametros.get(i) : "";
    }

    @Override
    public String toString() {
        String params = parametros.stream()
                .map(p -> p.length() > 40 ? p.substring(0, 37) + "..." : p)
                .map(p -> "\"" + p + "\"")
                .reduce((a, b) -> a + ", " + b).orElse("");
        return String.format("#%-4d %-19s %-11s %-11s %s",
                id, timestamp.format(FORMATO), tipo, status, params);
    }
}
