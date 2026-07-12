/**
 * sim4da — a Java framework for simulating distributed algorithms.
 *
 * <p>The module exports exactly one package, {@code org.oxoo2a.sim4da}.
 * The simulation core ({@code Network}, {@code NodeProxy},
 * {@code Mailbox}, {@code MessageInTransit}, {@code EventLog}, and the
 * still-empty {@code Topology} and {@code BellTower} stubs) lives in
 * {@code org.oxoo2a.sim4da.internal} and is deliberately <em>not</em>
 * exported. A student's code cannot import or reach those classes —
 * which is precisely the point. The teaching contract is "extend
 * {@link org.oxoo2a.sim4da.Node}, override {@code engage}, use the
 * four verbs"; if patching the simulator core looks easier than that,
 * the framework has failed pedagogically. JPMS makes "patch the
 * simulator core" a compile error rather than a tempting shortcut.
 *
 * <p>The module declares no {@code requires} directive. sim4da has
 * zero non-JDK dependencies — its built-in {@code EventLog} replaces
 * what would otherwise have been an SLF4J / Logback transitive
 * footprint. The framework distributes as a single, standalone JAR.
 */
module org.oxoo2a.sim4da {
    exports org.oxoo2a.sim4da;
}
