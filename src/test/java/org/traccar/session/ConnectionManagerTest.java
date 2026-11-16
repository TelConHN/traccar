package org.traccar.session;

import io.netty.util.Timer;
import org.junit.jupiter.api.Test;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.database.DeviceLookupService;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConnectionManagerTest {

    @Test
    public void testReconnectBeforeKeepaliveReloadsDevices() throws Exception {

        Storage storage = mock(Storage.class);
        ConnectionManager connectionManager = new ConnectionManager(
                new Config(), mock(CacheManager.class), storage, mock(NotificationManager.class),
                mock(Timer.class), mock(BroadcastService.class), mock(DeviceLookupService.class));

        Device device = new Device();
        device.setId(10);
        Position position = new Position();
        position.setDeviceId(10);

        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());
        var first = mock(ConnectionManager.UpdateListener.class);
        connectionManager.addListener(1, first);
        connectionManager.updatePosition(false, position);
        verify(first, never()).onUpdatePosition(position);

        connectionManager.removeListener(1, first);

        // The device is linked to the user while disconnected; the user reconnects before the keepalive cleanup.
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of(device));
        var second = mock(ConnectionManager.UpdateListener.class);
        connectionManager.addListener(1, second);
        connectionManager.updatePosition(false, position);
        verify(second).onUpdatePosition(position);
        verify(first, never()).onUpdatePosition(position);

        // A second connection of the same user does not lose the device list.
        var third = mock(ConnectionManager.UpdateListener.class);
        connectionManager.addListener(1, third);
        connectionManager.updatePosition(false, position);
        verify(third).onUpdatePosition(position);
    }

}
