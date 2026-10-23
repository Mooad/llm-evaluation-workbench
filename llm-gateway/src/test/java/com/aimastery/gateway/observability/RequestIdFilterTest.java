package com.aimastery.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void genere_un_id_visible_dans_les_logs_et_la_reponse() throws Exception {
        AtomicReference<String> seenDuringRequest = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                        seenDuringRequest.set(MDC.get(CallContext.REQUEST_ID));
                    }
                });

        assertThat(seenDuringRequest.get()).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenDuringRequest.get());
        // Le thread repart dans le pool : l'id ne doit pas fuir sur la requete suivante.
        assertThat(MDC.get(CallContext.REQUEST_ID)).isNull();
    }

    @Test
    void reprend_l_id_fourni_par_l_appelant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "chatbot-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("chatbot-42");
    }

    @Test
    void remplace_un_id_qui_forgerait_une_fausse_ligne_de_log() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "x cost=0\nprovider=fake");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .doesNotContain(" ").doesNotContain("\n").doesNotContain("cost");
    }
}
