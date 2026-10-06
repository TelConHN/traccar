/*
 * Copyright 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api.resource;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.model.Action;
import org.traccar.model.BaseModel;
import org.traccar.model.Calendar;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.Maintenance;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
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

@Path("audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditResource extends BaseResource {

    // Cuánto después de un comando se sigue buscando la respuesta del equipo (si antes no se le mandó otro).
    private static final long RESULT_WINDOW = 24 * 60 * 60 * 1000L;

    private static final Map<String, Class<? extends BaseModel>> NAMED_TYPES = Map.of(
            "device", Device.class,
            "user", User.class,
            "group", Group.class,
            "geofence", Geofence.class,
            "driver", Driver.class,
            "calendar", Calendar.class,
            "maintenance", Maintenance.class);

    // userId: cuentas o clientes. Trae lo que hizo esa cuenta y también lo que se le hizo a sus carros
    // (p. ej. un comando que la central le mandó a un carro del cliente). deviceId: solo lo de esos carros.
    // actionType: login, command, edit, etc. Los tres filtros se combinan (y).
    @GET
    public List<Action> get(
            @QueryParam("from") Date from, @QueryParam("to") Date to,
            @QueryParam("userId") List<Long> userIds,
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("actionType") List<String> actionTypes) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        Map<Long, User> users = new HashMap<>();
        for (User user : storage.getObjects(User.class, new Request(new Columns.All()))) {
            users.put(user.getId(), user);
        }

        Set<Long> clientDevices = new HashSet<>();
        if (!userIds.isEmpty()) {
            for (var permission : storage.getPermissions(User.class, Device.class)) {
                User owner = users.get(permission.getOwnerId());
                if (owner != null && !owner.getAdministrator() && userIds.contains(owner.getId())) {
                    clientDevices.add(permission.getPropertyId());
                }
            }
        }

        List<Action> actions = new ArrayList<>();
        try (var stream = storage.getObjectsStream(Action.class, new Request(
                new Columns.All(),
                new Condition.Between("actionTime", from, to),
                new Order("actionTime")))) {
            stream.filter(action -> actionTypes.isEmpty() || actionTypes.contains(action.getActionType()))
                    .filter(action -> {
                        if (userIds.isEmpty() || userIds.contains(action.getUserId())) {
                            return true;
                        }
                        long deviceId = actionDeviceId(action);
                        return deviceId > 0 && clientDevices.contains(deviceId)
                                || "user".equals(action.getString("ownerType"))
                                        && userIds.contains(action.getLong("ownerId"))
                                || "user".equals(action.getObjectType()) && userIds.contains(action.getObjectId());
                    })
                    .filter(action -> deviceIds.isEmpty() || deviceIds.contains(actionDeviceId(action)))
                    .forEach(actions::add);
        }

        Map<String, Map<Long, String>> names = new HashMap<>();
        Function<String, Map<Long, String>> loadNames = type -> {
            Class<? extends BaseModel> clazz = NAMED_TYPES.get(type);
            Map<Long, String> result = new HashMap<>();
            if (clazz == User.class) {
                users.values().forEach(user -> result.put(user.getId(), userLabel(user)));
            } else if (clazz != null) {
                try {
                    for (BaseModel item : storage.getObjects(clazz, new Request(new Columns.All()))) {
                        result.put(item.getId(), modelName(item));
                    }
                } catch (StorageException e) {
                    throw new RuntimeException(e);
                }
            }
            return result;
        };

        for (Action action : actions) {
            User user = users.get(action.getUserId());
            if (user != null) {
                action.setUserEmail(user.getEmail());
                action.setUserName(userLabel(user));
            }
            if (action.getObjectType() != null && action.getObjectId() > 0) {
                action.setObjectName(names.computeIfAbsent(action.getObjectType(), loadNames)
                        .get(action.getObjectId()));
            }
            String ownerType = action.getString("ownerType");
            if (ownerType != null) {
                action.setOwnerName(names.computeIfAbsent(ownerType, loadNames).get(action.getLong("ownerId")));
            }
        }

        attachCommandResults(actions);
        return actions;
    }

    private static long actionDeviceId(Action action) {
        if ("device".equals(action.getObjectType())) {
            return action.getObjectId();
        }
        if ("device".equals(action.getString("ownerType"))) {
            return action.getLong("ownerId");
        }
        return 0;
    }

    private static String userLabel(User user) {
        return user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getEmail();
    }

    private static String modelName(BaseModel item) {
        if (item instanceof Device device) {
            return device.getName();
        } else if (item instanceof Group group) {
            return group.getName();
        } else if (item instanceof Geofence geofence) {
            return geofence.getName();
        } else if (item instanceof Driver driver) {
            return driver.getName();
        } else if (item instanceof Calendar calendar) {
            return calendar.getName();
        } else if (item instanceof Maintenance maintenance) {
            return maintenance.getName();
        }
        return null;
    }

    // La respuesta del equipo llega como evento commandResult y no dice a qué comando contesta. Se toma la
    // primera que llegó (hora del servidor) después del comando y antes del siguiente comando a ese mismo
    // carro, hasta 24 h. Un comando en cola cuenta desde que el equipo se conectó y se le entregó
    // (evento queuedCommandSent con el id de la cola).
    private void attachCommandResults(List<Action> actions) throws StorageException {
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
