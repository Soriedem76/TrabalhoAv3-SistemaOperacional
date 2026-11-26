package simulador;

/** Teste rápido do núcleo (Pessoa A). Execute: java -cp out simulador.DemoNucleo (após compilar com javac -d out src/simulador/*.java) */
public class DemoNucleo {
    public static void main(String[] args) {
        FileSystemSimulator fs = FileSystemSimulator.criarNovo("data/meu.fs");

        fs.criarDiretorio("/docs");
        fs.criarDiretorio("/docs/trabalhos");
        fs.criarArquivo("/docs/notas.txt", "Olá, sistema de arquivos!");
        fs.copiarArquivo("/docs/notas.txt", "/docs/trabalhos");
        fs.copiarArquivo("/docs/notas.txt", "/docs/backup.txt");
        fs.renomearArquivo("/docs/backup.txt", "notas-bkp.txt");
        fs.renomearDiretorio("/docs/trabalhos", "entregas");

        System.out.println("Conteúdo de /docs:");
        fs.listar("/docs").forEach(l -> System.out.println("  " + l));

        fs.apagarArquivo("/docs/notas-bkp.txt");

        try {
            fs.apagarDiretorio("/docs"); // não está vazio -> erro esperado
        } catch (FsException e) {
            System.out.println("Erro esperado: " + e.getMessage());
        }

        fs.salvar("data/meu.fs");
        FileSystemSimulator recarregado = FileSystemSimulator.carregar("data/meu.fs");
        System.out.println("Após recarregar, /docs contém:");
        recarregado.listar("/docs").forEach(l -> System.out.println("  " + l));
    }
}
