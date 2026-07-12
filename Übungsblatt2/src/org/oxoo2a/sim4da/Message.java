package org.oxoo2a.sim4da;

/**
 * Marker interface for messages exchanged between nodes.
 *
 * <p>Concrete messages should be implemented as records:
 *
 * <pre>{@code
 *     public record Token(int value)    implements Message {}
 *     public record EndMessage()        implements Message {}
 *     public record Probe(String from)  implements Message {}
 * }</pre>
 *
 * <p>Records give messages two properties that matter for a distributed-
 * algorithm simulator:
 *
 * <ul>
 *   <li><b>Immutability</b> — once constructed, a record cannot be modified.
 *       The simulator can therefore share the same instance with every
 *       receiver of a broadcast without defensive copying.</li>
 *   <li><b>Pattern-matching at the receive site</b> — the canonical receive
 *       loop looks like this:
 *       <pre>{@code
 *           switch (receive().message()) {
 *               case Token(int v)   -> send(new Token(v + 1), nextId);
 *               case EndMessage e   -> { ...; running = false; }
 *               default             -> throw new IllegalStateException(...);
 *           }
 *       }</pre>
 *       Because record components are deconstructed by the {@code case}
 *       label, the message's data is bound directly into local variables.</li>
 * </ul>
 *
 * <p>Implementations are not required to be records — any class that
 * implements {@code Message} works — but anything that is not effectively
 * immutable risks the usual aliasing hazards once it crosses the network.
 *
 * <h2>A note on deep immutability</h2>
 *
 * <p>Records guarantee that their components are <em>assigned once</em>, but
 * they don't recursively freeze what those components point at. A record
 * whose component is a mutable container is only <em>shallowly</em>
 * immutable:
 *
 * <pre>{@code
 *     record Snapshot(List<Integer> values) implements Message {}
 *
 *     List<Integer> backing = new ArrayList<>(List.of(1, 2, 3));
 *     send(new Snapshot(backing), "neighbor");
 *     backing.add(4);   // the receiver's snapshot now reads 4 elements
 * }</pre>
 *
 * <p>Two idioms keep this safe:
 *
 * <ol>
 *   <li><b>Prefer immutable component types.</b> Primitives, {@code String},
 *       other records, {@code List.of(...)} / {@code Map.of(...)} /
 *       {@code Set.of(...)}, frozen instances of your own classes. Most
 *       messages have no reason to carry a mutable container.</li>
 *   <li><b>Defensive-copy in a compact constructor</b> when a message must
 *       accept a mutable input:
 *       <pre>{@code
 *           record Snapshot(List<Integer> values) implements Message {
 *               Snapshot { values = List.copyOf(values); }
 *           }
 *       }</pre>
 *       {@code List.copyOf}, {@code Map.copyOf}, and {@code Set.copyOf}
 *       return unmodifiable copies. For arrays, use {@code arr.clone()}.</li>
 * </ol>
 */
public interface Message {}
