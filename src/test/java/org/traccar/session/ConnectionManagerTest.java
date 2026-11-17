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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    public void testRefreshWhileConnectedStartsAndStopsUpdates() throws Exception {

        Storage storage = mock(Storage.class);
        ConnectionManager connectionManager = new ConnectionManager(
                new Config(), mock(CacheManager.class), storage, mock(NotificationManager.class),
                mock(Timer.class), mock(BroadcastService.class), mock(DeviceLookupService.class));

        Device device = new Device();
        device.setId(10);
        device.setPositionId(100);
        Position latest = new Position();
        latest.setId(100);
        latest.setDeviceId(10);
        Position position = new Position();
        position.setDeviceId(10);

        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());
        var listener = mock(ConnectionManager.UpdateListener.class);
        connectionManager.addListener(1, listener);

        // Linked while the user is connected: the device and its latest position are sent at once.
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of(device));
        when(storage.getObject(eq(Position.class), any(Request.class))).thenReturn(latest);
        connectionManager.refreshUserDevices(1);
        verify(listener).onUpdateDevice(device);
        verify(listener).onUpdatePosition(latest);
        connectionManager.updatePosition(false, position);
        verify(listener).onUpdatePosition(position);

        // Refreshing again with the same devices sends nothing new.
        connectionManager.refreshUserDevices(1);
        verify(listener, times(1)).onUpdateDevice(device);

        // Unlinked while connected: no more updates for that device.
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());
        connectionManager.refreshUserDevices(1);
        Position after = new Position();
        after.setDeviceId(10);
        connectionManager.updatePosition(false, after);
        verify(listener, never()).onUpdatePosition(after);

        // Users without an open connection are not queried.
        var calls = mockingDetails(storage).getInvocations().size();
        connectionManager.refreshUserDevices(2);
        assertEquals(calls, mockingDetails(storage).getInvocations().size());
    }

}
