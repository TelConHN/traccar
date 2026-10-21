package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.Server;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VoltageEventHandlerTest extends BaseTest {

    private VoltageEventHandler handler(Device device) {
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(Device.class, 1L)).thenReturn(device);
        when(cacheManager.getServer()).thenReturn(new Server());
        return new VoltageEventHandler(cacheManager);
    }

    private static Device device() {
        Device device = new Device();
        device.setId(1);
        return device;
    }

    private static List<String> feed(VoltageEventHandler handler, double... voltages) {
        List<String> alarms = new ArrayList<>();
        for (double voltage : voltages) {
            Position position = new Position();
            position.setDeviceId(1);
            position.setTime(new Date());
            position.set(Position.KEY_POWER, voltage);
            handler.analyzePosition(position, (Event event) -> alarms.add(event.getString(Position.KEY_ALARM)));
        }
        return alarms;
    }

    @Test
    public void testCrankingDipIsIgnored() {
        assertTrue(feed(handler(device()), 12.6, 10.2, 14.1, 10.5, 13.9).isEmpty());
    }

    @Test
    public void testLowVoltageOnce() {
        assertEquals(List.of(VoltageEventHandler.ALARM_LOW_VOLTAGE),
                feed(handler(device()), 12.6, 11.2, 11.1, 11.0, 11.3, 11.9, 11.2, 11.1));
    }

    @Test
    public void testLowVoltageAgainAfterRecovery() {
        assertEquals(List.of(VoltageEventHandler.ALARM_LOW_VOLTAGE, VoltageEventHandler.ALARM_LOW_VOLTAGE),
                feed(handler(device()), 12.6, 11.2, 11.1, 12.4, 11.0, 10.9));
    }

    @Test
    public void testDisconnectAndReconnect() {
        assertEquals(
                List.of(VoltageEventHandler.ALARM_POWER_DISCONNECTED, VoltageEventHandler.ALARM_POWER_RECONNECTED),
                feed(handler(device()), 12.6, 0.0, 0.0, 12.5, 12.6));
    }

    @Test
    public void testNeverArmedWithoutNormalReading() {
        // Equipos que mandan "power" en otra escala (nivel 0-6) o siempre en cero.
        assertTrue(feed(handler(device()), 0, 0, 4, 3, 0, 5).isEmpty());
    }

    @Test
    public void testDisabledByDevice() {
        Device device = device();
        device.set("lowVoltageThreshold", 0.0);
        assertTrue(feed(handler(device), 12.6, 0.0, 11.0, 11.0).isEmpty());
    }

    @Test
    public void testTruckThreshold() {
        Device device = device();
        device.set("lowVoltageThreshold", 23.0);
        assertEquals(List.of(VoltageEventHandler.ALARM_LOW_VOLTAGE),
                feed(handler(device), 26.5, 22.4, 22.3));
    }

}
