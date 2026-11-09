package com.wifibilling.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side-effect work (network I/O: RADIUS disconnects, emails, provider
 * HTTP) after the surrounding transaction commits. Such calls must never
 * execute inside a transaction: while blocked on a socket the transaction
 * sits "idle in transaction" holding row/table locks, which stalls writers
 * and blocks schema migrations indefinitely.
 *
 * Uses afterCompletion rather than afterCommit: by then the resources are
 * unbound, so the work runs on a clean thread where repository calls get
 * their own fresh transactions (an afterCommit runnable can join the dead
 * transaction still bound to the thread).
 */
@Slf4j
public final class PostCommit {

    private PostCommit() {}

    /** Runs work once the active transaction commits; runs it now when there is none. */
    public static void run(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) return;
                try {
                    work.run();
                } catch (Exception e) {
                    log.error("Post-commit task failed: {}", e.getMessage(), e);
                }
            }
        });
    }
}
