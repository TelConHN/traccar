/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler.events;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Muchos GPS no mandan alarma de batería baja o de batería desconectada, pero sí el voltaje del vehículo
// (power). Aquí se mide. Las alarmas tienen nombre propio para no duplicar los avisos que ya salen con las
// del equipo (lowPower, powerCut): solo avisan si alguien las elige en una notificación.
//  - lowVoltage: dos reportes seguidos bajo el umbral (uno solo es el bajón normal al arrancar el motor).
//  - powerDisconnected: el voltaje cae a casi cero (le quitaron la batería o cortaron el cable).
//  - powerReconnected: vuelve después de una desconexión.
// Solo se avisa después de haber visto un voltaje normal desde que arrancó el servidor: así un equipo que
// manda "power" en otra escala (o siempre 0) no salta nunca, y un reinicio no repite las alarmas.
@Singleton
public class VoltageEventHandler extends BaseEventHandler {

    public static final String ALARM_LOW_VOLTAGE = "lowVoltage";
    public static final String ALARM_POWER_DISCONNECTED = "powerDisconnected";
    public static final String ALARM_POWER_RECONNECTED = "powerReconnected";

    private static final double DISCONNECTED_VOLTAGE = 1.0;
    // Cuánto tiene que subir sobre el umbral para dar la batería por recuperada (evita alarmas en vaivén).
    private static final double HYSTERESIS = 0.5;

    private enum Level { NORMAL, LOW, DISCONNECTED }

    private static final class State {
        private Level level = Level.NORMAL;
        private int lowCount;
    }

    private final CacheManager cacheManager;
    private final Map<Long, State> states = new ConcurrentHashMap<>();

    @Inject
    public VoltageEventHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null || !PositionUtil.isLatest(cacheManager, position)
                || !position.hasAttribute(Position.KEY_POWER)) {
            return;
        }
        double threshold = AttributeUtil.lookup(
                cacheManager, Keys.EVENT_LOW_VOLTAGE_THRESHOLD, position.getDeviceId());
        if (threshold <= 0) {
            states.remove(position.getDeviceId());
            return;
        }

        double power = position.getDouble(Position.KEY_POWER);
        State state = states.get(position.getDeviceId());
        if (state == null) {
            if (power >= threshold + HYSTERESIS) {
                states.put(position.getDeviceId(), new State());
            }
            return;
        }

        if (power < DISCONNECTED_VOLTAGE) {
            if (state.level != Level.DISCONNECTED) {
                state.level = Level.DISCONNECTED;
                callback.eventDetected(alarm(position, ALARM_POWER_DISCONNECTED, power, threshold));
            }
        } else if (power < threshold) {
            state.lowCount += 1;
            if (state.level == Level.NORMAL && state.lowCount >= 2) {
                state.level = Level.LOW;
                callback.eventDetected(alarm(position, ALARM_LOW_VOLTAGE, power, threshold));
            }
        } else {
            state.lowCount = 0;
            if (power >= threshold + HYSTERESIS) {
                if (state.level == Level.DISCONNECTED) {
                    callback.eventDetected(alarm(position, ALARM_POWER_RECONNECTED, power, threshold));
                }
                state.level = Level.NORMAL;
            }
        }
    }

    private static Event alarm(Position position, String alarm, double power, double threshold) {
        Event event = new Event(Event.TYPE_ALARM, position);
        event.set(Position.KEY_ALARM, alarm);
        event.set(Position.KEY_POWER, Math.round(power * 100) / 100.0);
        event.set("threshold", threshold);
        return event;
    }

}
