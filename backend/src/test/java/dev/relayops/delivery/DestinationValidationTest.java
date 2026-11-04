package dev.relayops.delivery;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DestinationValidationTest {
    static final Set<String> HOSTS=Set.of("allowed.example");

    @Test void externalUrlMustBeAllowlistedHttpsWithFixedPathAndPort() {
        assertTrue(DestinationService.validExternal("https://allowed.example/relayops/events",HOSTS));
        for(String bad:new String[]{"http://allowed.example/relayops/events","https://other.example/relayops/events",
                "https://allowed.example/other","https://allowed.example/relayops/events?x=1",
                "https://allowed.example:8443/relayops/events","https://user@allowed.example/relayops/events",
                "https://allowed.example/relayops/events#frag","https://127.0.0.1/relayops/events"})
            assertFalse(DestinationService.validExternal(bad,HOSTS),bad);
    }

    @Test void hostAllowlistRejectsIpsAndSingleLabels() {
        assertEquals(Set.of("a.example.com","b.example.com"),DestinationService.parseHosts(" A.Example.com, b.example.com "));
        for(String bad:new String[]{"localhost","127.0.0.1","a_b.example.com","-bad.example.com","example.com:443"})
            assertThrows(IllegalStateException.class,()->DestinationService.parseHosts(bad),bad);
    }

    @Test void transportRejectsLoopbackAndPrivateHttpsDestinations() {
        assertFalse(WebhookTransport.safeRemoteHost("localhost"));
        assertFalse(WebhookTransport.safeRemoteHost("127.0.0.1"));
        assertFalse(WebhookTransport.safeRemoteHost("10.0.0.1"));
        assertFalse(WebhookTransport.safeRemoteHost("169.254.169.254"));
        assertFalse(WebhookTransport.safeRemoteHost("fe80::1"));
    }
}
