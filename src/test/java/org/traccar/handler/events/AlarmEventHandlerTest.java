package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.config.Config;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AlarmEventHandlerTest extends BaseTest {

    @Test
    public void testAlarmEventHandler() {

        AlarmEventHandler alarmEventHandler = new AlarmEventHandler(new Config(), mock(CacheManager.class));

        Position position = new Position();
        position.addAlarm(Position.ALARM_GENERAL);
        List<Event> events = new ArrayList<>();
        alarmEventHandler.analyzePosition(position, events::add);
        assertFalse(events.isEmpty());
        Event event = events.iterator().next();
        assertEquals(Event.TYPE_ALARM, event.getType());

    }

    @Test
    public void testAlarmWithOlderFixTime() {

        Position last = new Position();
        last.setDeviceId(1);
        last.setFixTime(new Date(1_000_000_000_000L));
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getPosition(1)).thenReturn(last);

        AlarmEventHandler alarmEventHandler = new AlarmEventHandler(new Config(), cacheManager);

        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(0));
        position.addAlarm(Position.ALARM_SOS);
        List<Event> events = new ArrayList<>();
        alarmEventHandler.analyzePosition(position, events::add);
        assertEquals(1, events.size());
        assertEquals(Position.ALARM_SOS, events.get(0).getString(Position.KEY_ALARM));

    }

}
