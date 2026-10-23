package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.config.ResilienceProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class ProviderGuardTest {

    private static final ChatResult OK = ChatResult.of("ok", "m", new Usage(1, 1), "stop");

    private final MutableClock clock = new MutableClock();
    private final List<Long> pauses = new ArrayList<>();
    private final ProviderGuard guard = new ProviderGuard(
            new ResilienceProperties(2, 300, 3, 30), clock, pauses::add);

    @Test
    void reessaie_une_panne_passagere_avant_de_laisser_basculer() {
        AtomicInteger calls = new AtomicInteger();

        ChatResult result = guard.call("p", () -> calls.incrementAndGet() == 1 ? throwUnavailable() : OK, () -> true);

        assertThat(result).isEqualTo(OK);
        assertThat(calls).hasValue(2);
        assertThat(pauses).containsExactly(300L);
    }

    @Test
    void ne_reessaie_pas_une_erreur_definitive() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.call("p", () -> {
            calls.incrementAndGet();
            throw new ProviderCallException("p", "requete invalide",
                    HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null));
        }, () -> true)).isInstanceOf(ProviderCallException.class);

        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void ne_reessaie_pas_un_flux_deja_commence() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> guard.call("p", () -> {
            calls.incrementAndGet();
            return throwUnavailable();
        }, () -> false)).isInstanceOf(ProviderCallException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void ouvre_le_circuit_apres_trois_pannes_et_ecarte_le_fournisseur_sans_l_appeler() {
        failTwice(); // 2 pannes (1 appel + 1 retry)
        failTwice(); // 3e panne : circuit ouvert, pas de retry

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> guard.call("p", () -> {
            calls.incrementAndGet();
            return OK;
        }, () -> true)).hasMessageContaining("circuit ouvert");

        assertThat(calls).hasValue(0);
        assertThat(guard.circuits().get("p").state()).isEqualTo(ProviderGuard.State.OPEN);
    }

    @Test
    void apres_le_delai_un_appel_d_essai_reussi_referme_le_circuit() {
        failTwice();
        failTwice();
        clock.advance(Duration.ofSeconds(31));
        assertThat(guard.circuits().get("p").state()).isEqualTo(ProviderGuard.State.HALF_OPEN);

        guard.call("p", () -> OK, () -> true);

        assertThat(guard.circuits().get("p").state()).isEqualTo(ProviderGuard.State.CLOSED);
    }

    @Test
    void un_timeout_de_lecture_n_est_pas_reessaye() {
        ProviderCallException timeout = new ProviderCallException("p", "lent",
                new org.springframework.web.client.ResourceAccessException("Read timed out",
                        new java.net.SocketTimeoutException("Read timed out")));

        assertThat(FailureClassifier.isTransient(timeout)).isFalse();
        assertThat(FailureClassifier.isTransient(new ProviderCallException("p", "flux", null, 503))).isTrue();
    }
    @Test
    void un_408_est_reessaye() {
        AtomicInteger calls = new AtomicInteger();

        ChatResult result = guard.call("p", () -> calls.incrementAndGet() == 1 ? throwRequestTimout() : OK, () -> true);

        assertThat(result).isEqualTo(OK);
        assertThat(calls).hasValue(2);
        assertThat(pauses).containsExactly(300L);
    }

    private void failTwice() {
        try {
            guard.call("p", this::throwUnavailable, () -> true);
        } catch (ProviderCallException expected) {
            // attendu
        }
    }

    private ChatResult throwUnavailable() {
        throw new ProviderCallException("p", "surcharge",
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "503", null, null, null));
    }


    private ChatResult throwRequestTimout() {
        throw new ProviderCallException("p", "surcharge",
                HttpServerErrorException.create(HttpStatus.REQUEST_TIMEOUT, "408", null, null, null));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
