package dev.agentops;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration(proxyBeanMethods=false)
class SessionRaceTestConfiguration {
    @Bean @Primary GatePasswordEncoder gatePasswordEncoder() { return new GatePasswordEncoder(); }

    static class GatePasswordEncoder implements PasswordEncoder {
        private final AtomicReference<Gate> next = new AtomicReference<>();
        private final PasswordEncoder delegate = new BCryptPasswordEncoder(12);
        @Override public String encode(CharSequence raw) { return delegate.encode(raw); }
        Gate arm() { Gate gate = new Gate(); next.set(gate); return gate; }
        @Override public boolean matches(CharSequence raw, String encoded) {
            Gate gate = next.getAndSet(null);
            if (gate != null) {
                gate.entered.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Password gate timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return delegate.matches(raw, encoded);
        }
    }
    static class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }
}
