package io.lifeengine.runtime.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * UN solo PostgreSQL para toda la corrida, y una base nueva por clase de test (KAN-296).
 *
 * <h2>El problema que resuelve</h2>
 *
 * <p>Diez clases de test arrancaban cada una su propio contenedor. Dentro de un job eso es
 * secuencial y no molesta; el problema aparece en el runner, donde los jobs de seis repos
 * comparten un único Docker <b>rootless</b>. Cada arranque pide un puerto del host a
 * RootlessKit, y con suficiente rotación dos contenedores piden el mismo:
 *
 * <pre>
 * Status 500: failed to set up container networking: driver failed programming external
 * connectivity ... error while calling RootlessKit PortManager.AddPort():
 * listen tcp4 0.0.0.0:33046: bind: address already in use
 * </pre>
 *
 * <p>El 2026-09-06 eso produjo <b>seis falsos rojos</b>, siempre en una de esas clases:
 * {@code RetrievalFailuresAreLoudTest}, {@code OrganizationCreationAtomicityTest},
 * {@code EmailTriageIntegrationTest}, {@code ReplySendSchemaGuaranteesTest} y
 * {@code CrossTenantConversationLeakTest}. Todas pasaban al relanzar sin tocar una línea.
 *
 * <p>Un rojo que la mitad de las veces no significa nada enseña a ignorar los rojos, y ahí se
 * pierde el gate entero.
 *
 * <h2>Por qué esto y no un retry</h2>
 *
 * <p>Un reintento esconde la colisión y la deja crecer. Esto la <b>elimina</b>: business-chat
 * pasa de 10 pedidos de puerto a 1, y la flota de seis repos de ~20 a 5. El aislamiento entre
 * clases no se pierde — cada una recibe una base propia, creada y migrada para ella.
 *
 * <p>Lo que <b>no</b> se hace es serializar la CI ni bajar la concurrencia: eso cambiaría el
 * síntoma por lentitud sin tocar la causa.
 *
 * <h2>Por qué el contenedor no se para</h2>
 *
 * <p>No hay {@code stop()}. Lo reclama Ryuk cuando termina la JVM, que es el patrón singleton
 * de Testcontainers. Pararlo en un {@code @AfterAll} sería devolver el puerto y volver a
 * pedirlo en la clase siguiente — exactamente la rotación que causa el problema.
 */
public final class SharedPostgres {

    /** La misma imagen que corre la plataforma: validar contra otra versión es validar otra cosa. */
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg16")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("life_engine_runtime_shared_it")
                    .withUsername("life")
                    .withPassword("life-it-only");

    private static final AtomicInteger SECUENCIA = new AtomicInteger();
    private static volatile boolean arrancado;

    private SharedPostgres() {}

    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Coordenadas de una base: lo único que un test necesita saber.
     *
     * <p>Trae host, puerto y nombre por separado además de la URL porque varios tests arman
     * la conexión r2dbc por opciones, no por cadena.
     */
    public record Db(String jdbcUrl, String usuario, String password, String host, int puerto, String base) {
        public String r2dbcUrl() {
            return "r2dbc:postgresql://" + host + ":" + puerto + "/" + base;
        }
    }

    /**
     * Una base NUEVA para esta clase de test, con las migraciones ya aplicadas.
     *
     * @param etiqueta nombre corto de la clase, sólo para que el nombre de la base diga de
     *     quién es cuando haya que mirar el contenedor a mano.
     */
    public static Db freshDatabase(String etiqueta) {
        arrancar();
        String nombre =
                ("it_" + etiqueta.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") + "_"
                                + SECUENCIA.incrementAndGet())
                        .replaceAll("_+", "_");
        crear(nombre);
        Db db = coordenadas(nombre);
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.usuario(), db.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return db;
    }

    /** Igual que {@link #freshDatabase} pero sin migrar: para los tests que migran ellos. */
    public static Db emptyDatabase(String etiqueta) {
        arrancar();
        String nombre =
                ("it_" + etiqueta.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") + "_"
                                + SECUENCIA.incrementAndGet())
                        .replaceAll("_+", "_");
        crear(nombre);
        return coordenadas(nombre);
    }

    private static synchronized void arrancar() {
        if (!arrancado) {
            CONTAINER.start();
            arrancado = true;
        }
    }

    private static void crear(String nombre) {
        try (Connection c =
                        DriverManager.getConnection(
                                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement st = c.createStatement()) {
            // El nombre lo compone esta clase a partir de un identificador de test y un
            // contador, no de entrada externa; aun así se restringe a [a-z0-9_] arriba.
            st.executeUpdate("CREATE DATABASE " + nombre);
        } catch (Exception ex) {
            throw new IllegalStateException("no se pudo crear la base de test " + nombre, ex);
        }
    }

    private static Db coordenadas(String nombre) {
        String base = CONTAINER.getJdbcUrl();
        String conNombre = base.replaceFirst("/" + CONTAINER.getDatabaseName() + "(\\?|$)", "/" + nombre + "$1");
        return new Db(
                conNombre,
                CONTAINER.getUsername(),
                CONTAINER.getPassword(),
                CONTAINER.getHost(),
                CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                nombre);
    }
}
