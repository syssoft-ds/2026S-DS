package org.oxoo2a.sim4da.internal;

import org.oxoo2a.sim4da.SimulationBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The per-node inbox queue. Senders {@link #put} a message; the owning
 * receiver blocks in {@link #take} until at least one message is present,
 * then removes the message at the index chosen by
 * {@link SimulationBehavior#selectMessageInQueue(int)}.
 *
 * <p>Letting the simulator pick an arbitrary index — instead of always the
 * head — is what models non-FIFO delivery without changing the user-facing
 * API. {@code BlockingQueue.take()} could replace this implementation if
 * FIFO were sufficient; it is not, which is why the lock and condition are
 * explicit here.
 */
final class Mailbox {

    private final List<MessageInTransit> messages = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    void put(MessageInTransit mit) {
        lock.lock();
        try {
            messages.add(mit);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    MessageInTransit take() throws InterruptedException {
        lock.lock();
        try {
            while (messages.isEmpty()) {
                notEmpty.await();
            }
            int index = SimulationBehavior.selectMessageInQueue(messages.size());
            return messages.remove(index);
        } finally {
            lock.unlock();
        }
    }
}
