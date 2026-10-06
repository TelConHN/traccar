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
package org.traccar.helper.model;

import org.traccar.model.Action;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// Lo usan el reporte de Auditoría y el historial de límites de velocidad.
public final class CommandResultUtil {

    // Cuánto después de un comando se sigue buscando la respuesta del equipo (si antes no se le mandó otro).
    private static final long RESULT_WINDOW = 24 * 60 * 60 * 1000L;

    private CommandResultUtil() {
    }

    // La respuesta del equipo llega como evento commandResult y no dice a qué comando contesta. Se toma la
    // primera que llegó (hora del servidor) después del comando y antes del siguiente comando a ese mismo
    // carro, hasta 24 h. Un comando en cola cuenta desde que el equipo se conectó y se le entregó
    // (evento queuedCommandSent con el id de la cola).
    public static void attach(Storage storage, List<Action> actions) throws StorageException {
        Map<Long, List<Action>> commandsByDevice = new HashMap<>();
        for (Action action : actions) {
            if ("command".equals(action.getActionType()) && "device".equals(action.getObjectType())
                    && !"failed".equals(action.getString("status"))) {
                commandsByDevice.computeIfAbsent(action.getObjectId(), k -> new ArrayList<>()).add(action);
            }
        }

        for (var entry : commandsByDevice.entrySet()) {
            long deviceId = entry.getKey();
            List<Action> commands = entry.getValue();
            commands.sort(Comparator.comparing(Action::getActionTime));

            // eventTime es la hora del equipo, que puede venir corrida: se busca con margen y se compara
            // con la hora del servidor de la posición.
            Date first = new Date(commands.get(0).getActionTime().getTime() - RESULT_WINDOW);
            Date last = new Date(commands.get(commands.size() - 1).getActionTime().getTime() + 2 * RESULT_WINDOW);
            List<long[]> results = new ArrayList<>();
            Map<Long, String> resultTexts = new HashMap<>();
            Map<Long, Date> queuedSent = new HashMap<>();
            for (Event event : storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.Between("eventTime", first, last))))) {
                if (Event.TYPE_COMMAND_RESULT.equals(event.getType())) {
                    Date received = event.getEventTime();
                    if (event.getPositionId() > 0) {
                        Position position = storage.getObject(Position.class, new Request(
                                new Columns.Include("serverTime"),
                                new Condition.Equals("id", event.getPositionId())));
                        if (position != null && position.getServerTime() != null) {
                            received = position.getServerTime();
                        }
                    }
                    results.add(new long[] {received.getTime(), event.getId()});
                    resultTexts.put(event.getId(), event.getString(Position.KEY_RESULT));
                } else if (Event.TYPE_QUEUED_COMMAND_SENT.equals(event.getType())) {
                    queuedSent.put(event.getLong("id"), event.getEventTime());
                }
            }
            results.sort(Comparator.comparingLong(result -> result[0]));

            // Hora en que el comando le llegó de verdad al equipo; uno que sigue en cola no tiene.
            List<Action> delivered = new ArrayList<>();
            for (Action command : commands) {
                long queuedCommandId = command.getLong("queuedCommandId");
                if (queuedCommandId > 0) {
                    Date sent = queuedSent.get(queuedCommandId);
                    if (sent != null) {
                        command.setQueuedSentTime(sent);
                        delivered.add(command);
                    }
                } else {
                    delivered.add(command);
                }
            }
            Function<Action, Long> deliveredAt = command -> command.getQueuedSentTime() != null
                    ? command.getQueuedSentTime().getTime() : command.getActionTime().getTime();
            delivered.sort(Comparator.comparing(deliveredAt));

            Set<Long> used = new HashSet<>();
            for (int i = 0; i < delivered.size(); i++) {
                Action command = delivered.get(i);
                long start = deliveredAt.apply(command);
                long end = start + RESULT_WINDOW;
                // Varios comandos en cola se entregan juntos: el límite es el siguiente que salió después.
                for (int j = i + 1; j < delivered.size(); j++) {
                    long next = deliveredAt.apply(delivered.get(j));
                    if (next > start) {
                        end = Math.min(end, next);
                        break;
                    }
                }
                for (long[] result : results) {
                    if (result[0] >= start && result[0] < end && !used.contains(result[1])) {
                        used.add(result[1]);
                        command.setCommandResult(resultTexts.get(result[1]));
                        command.setCommandResultTime(new Date(result[0]));
                        break;
                    }
                }
            }
        }
    }

}
