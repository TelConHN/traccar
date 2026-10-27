package org.traccar.api.security;

import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientRestrictionsTest {

    private static User client(String allowedReports) {
        User user = new User();
        if (allowedReports != null) {
            // Directo al mapa como lo deja el JSON de la API: set() descarta la cadena vacía
            // («ningún reporte»).
            user.getAttributes().put(ClientRestrictions.KEY_ALLOWED_REPORTS, allowedReports);
        }
        return user;
    }

    @Test
    public void testUnconfiguredClientKeepsReplayOnly() {
        assertTrue(ClientRestrictions.reportAllowed(client(null), "replay"));
        assertFalse(ClientRestrictions.reportAllowed(client(null), "trips"));
        assertFalse(ClientRestrictions.reportAllowed(client(null), "logs"));
    }

    @Test
    public void testConfiguredReports() {
        User user = client("trips,summary");
        assertTrue(ClientRestrictions.reportAllowed(user, "trips"));
        assertTrue(ClientRestrictions.reportAllowed(user, "route", "summary"));
        assertFalse(ClientRestrictions.reportAllowed(user, "replay", "route"));
        assertFalse(ClientRestrictions.reportAllowed(client(""), "replay"));
    }

    @Test
    public void testAdministratorAlwaysAllowed() {
        User user = client("");
        user.setAdministrator(true);
        assertTrue(ClientRestrictions.reportAllowed(user, "events"));
    }

    @Test
    public void testClientCannotGrantItselfServices() {
        User stored = client("trips");
        User sent = client("trips,events,combined");
        sent.set("bloqueoMotor", true);
        sent.set("speedLimitEnabled", true);
        sent.set("mapFollow", true);
        ClientRestrictions.keepProtected(sent, stored);
        assertEquals("trips", sent.getString(ClientRestrictions.KEY_ALLOWED_REPORTS));
        assertFalse(sent.hasAttribute("bloqueoMotor"));
        assertFalse(sent.hasAttribute("speedLimitEnabled"));
        // Lo que no es un permiso sí lo cambia el cliente.
        assertTrue(sent.getBoolean("mapFollow"));
    }

    @Test
    public void testClientCannotDropWhatItWasGiven() {
        User stored = client(null);
        stored.set("bloqueoMotor", true);
        User sent = client(null);
        ClientRestrictions.keepProtected(sent, stored);
        assertTrue(sent.getBoolean("bloqueoMotor"));
    }

    @Test
    public void testNewObjectsStartWithoutProtectedAttributes() {
        Device device = new Device();
        device.set("transporte", true);
        device.set("speedLimit", 40.0);
        device.set("color", "red");
        ClientRestrictions.keepProtected(device, null);
        assertNull(device.getAttributes().get("transporte"));
        assertNull(device.getAttributes().get("speedLimit"));
        assertEquals("red", device.getString("color"));
    }

}
