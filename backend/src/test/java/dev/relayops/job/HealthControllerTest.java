package dev.relayops.job;

import dev.relayops.operations.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTest {
    @Test void healthIsMinimalAndUncached() {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject("SELECT 1",Integer.class)).thenReturn(1);
        var response=new HealthController(jdbc).health();
        assertEquals(200,response.getStatusCode().value());assertEquals("UP",response.getBody().get("status"));
        assertEquals("1.0.0",response.getBody().get("version"));assertEquals(2,response.getBody().size());assertEquals("no-store",response.getHeaders().getCacheControl());
    }
    @Test void databaseFailureDoesNotExposeConnectionOrException() {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject("SELECT 1",Integer.class)).thenThrow(new DataAccessResourceFailureException("private-db-address/password"));
        var response=new HealthController(jdbc).health();assertEquals(503,response.getStatusCode().value());assertEquals("DOWN",response.getBody().get("status"));assertFalse(response.getBody().toString().contains("private"));
    }
}
