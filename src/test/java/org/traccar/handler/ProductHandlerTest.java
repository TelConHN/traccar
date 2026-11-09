package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProductHandlerTest {

    private static final long DEVICE_ID = 1;
    private static final long START = 1_800_000_000_000L;

    private CacheManager cacheManager;
    private ProductHandler handler;
    private Device device;
    private Position last;

    @BeforeEach
    public void setUp() {
        cacheManager = mock(CacheManager.class);
        device = new Device();
        device.setId(DEVICE_ID);
        when(cacheManager.getObject(Device.class, DEVICE_ID)).thenReturn(device);
        when(cacheManager.getConfig()).thenReturn(new Config());
        handler = new ProductHandler(cacheManager);
        last = null;
    }

    /** Procesa una posición a {@code seconds} del inicio y la deja como la última del vehículo. */
    private Position send(long seconds, Object... attributes) {
        Position position = new Position();
        position.setDeviceId(DEVICE_ID);
        position.setFixTime(new Date(START + seconds * 1000));
        for (int i = 0; i < attributes.length; i += 2) {
            position.getAttributes().put((String) attributes[i], attributes[i + 1]);
        }
        when(cacheManager.getPosition(DEVICE_ID)).thenReturn(last);
        handler.handlePosition(position, p -> { });
        if (last == null || !position.getFixTime().before(last.getFixTime())) {
            last = position;
        }
        return position;
    }

    @Test
    public void testReaderLoadsAndUnloads() {
        assertEquals("7501", send(0, "barcode", "7501").getString(Position.KEY_PRODUCTS));
        assertEquals("7501", send(30).getString(Position.KEY_PRODUCTS));
        assertEquals("7501,ABC", send(60, "barcode", "abc").getString(Position.KEY_PRODUCTS));
        // El lector repite la lectura anterior: cuenta una sola vez.
        assertEquals("7501,ABC", send(90, "barcode", "ABC").getString(Position.KEY_PRODUCTS));
        assertEquals("ABC", send(120, "barcode", "7501").getString(Position.KEY_PRODUCTS));
        assertEquals("ABC,123456", send(150, "io207", 123456L).getString(Position.KEY_PRODUCTS));
        assertEquals("123456", send(180, "barcode", "ABC").getString(Position.KEY_PRODUCTS));
    }

    @Test
    public void testDriverInputIsNeverUsed() {
        device.set("processing.productAttributes", "barcode,driverUniqueId");
        assertNull(send(0, Position.KEY_DRIVER_UNIQUE_ID, "CARD1").getString(Position.KEY_PRODUCTS));
    }

    @Test
    public void testNumericCodeKeepsItsDigits() {
        assertEquals("7501055300013", send(0, "barcode", 7501055300013.0).getString(Position.KEY_PRODUCTS));
    }

    @Test
    public void testTagStaysWhileSeenAndLeavesAfterTimeout() {
        assertEquals("A1B2", send(0, "tag1Id", "a1b2").getString(Position.KEY_PRODUCTS));
        assertEquals("A1B2", send(200).getString(Position.KEY_PRODUCTS));
        assertNull(send(301).getString(Position.KEY_PRODUCTS));
    }

    @Test
    public void testTagTimeoutPerDevice() {
        device.set("processing.productTagTimeout", 60L);
        send(0, "tag1Id", "a1b2");
        assertNull(send(61).getString(Position.KEY_PRODUCTS));
    }

    @Test
    public void testIbeaconUsesMajorAndMinor() {
        Position position = send(0, "beacon1Uuid", "ffee", "beacon1Major", 1, "beacon1Minor", 2);
        assertEquals("FFEE-1-2", position.getString(Position.KEY_PRODUCTS));
    }

    @Test
    public void testLateReportDoesNotChangeTheLoad() {
        send(100, "barcode", "7501");
        assertNull(send(50, "barcode", "7501").getString(Position.KEY_PRODUCTS));
        assertEquals("7501", send(130).getString(Position.KEY_PRODUCTS));
    }

}
