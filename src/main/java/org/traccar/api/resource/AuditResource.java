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
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.helper.model.CommandResultUtil;
import org.traccar.model.Action;
import org.traccar.model.BaseModel;
import org.traccar.model.Calendar;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.Maintenance;
import org.traccar.model.Notification;
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

    private static final Map<String, Class<? extends BaseModel>> NAMED_TYPES = Map.of(
            "device", Device.class,
            "user", User.class,
            "group", Group.class,
            "geofence", Geofence.class,
            "driver", Driver.class,
            "calendar", Calendar.class,
            "maintenance", Maintenance.class,
            "notification", Notification.class,
            "command", Command.class);

    // Lo que hace el GPS (geocercas, alarmas, desconexión…) no está en tc_actions sino en tc_events; se agrega
    // como acción "event". Un mes de todos los carros puede traer cientos de miles (sobre todo en línea /
    // fuera de línea): se mandan los primeros y el encabezado avisa que hubo más.
    private static final String ACTION_EVENT = "event";
    private static final int MAX_EVENTS = 20000;
    private static final int MAX_DEVICE_QUERIES = 50;
    private static final String EVENTS_TRUNCATED_HEADER = "X-Audit-Events-Truncated";

    // userId: cuentas o clientes. Trae lo que hizo esa cuenta y también lo que se le hizo a sus carros
    // (p. ej. un comando que la central le mandó a un carro del cliente). deviceId: solo lo de esos carros.
    // actionType: login, command, edit, event, etc. eventType: geofenceEnter, alarm (todas las alarmas),
    // alarm:powerCut (solo esa)…; elegir solo tipos de evento trae solo eventos. Los filtros se combinan (y).
    @GET
    public Response get(
            @QueryParam("from") Date from, @QueryParam("to") Date to,
            @QueryParam("userId") List<Long> userIds,
            @QueryParam("deviceId") List<Long> deviceIds,
            @QueryParam("actionType") List<String> actionTypes,
            @QueryParam("eventType") List<String> eventTypes) throws StorageException {
        permissionsService.checkAdmin(getUserId());

        boolean wantActions = actionTypes.isEmpty()
                ? eventTypes.isEmpty()
                : actionTypes.stream().anyMatch(type -> !ACTION_EVENT.equals(type));
        boolean wantEvents = actionTypes.isEmpty() || actionTypes.contains(ACTION_EVENT) || !eventTypes.isEmpty();

        Map<Long, User> users = new HashMap<>();
        for (User user : storage.getObjects(User.class, new Request(new Columns.All()))) {
            users.put(user.getId(), user);
        }

        // Carros de cada cuenta elegida, también los que ve por grupo. Para las acciones solo cuentan los de
        // clientes: con una cuenta administradora saldría todo lo que cualquiera le hizo a cualquier carro.
        // Para los eventos cuentan los de todas: son los carros de los que esa cuenta recibe avisos, y antes
        // una cuenta administradora no traía ningún evento.
        Set<Long> clientDevices = new HashSet<>();
        Set<Long> accountDevices = new HashSet<>();
        for (long userId : userIds) {
            User owner = users.get(userId);
            if (owner == null) {
                continue;
            }
            for (Device device : storage.getObjects(Device.class, new Request(
                    new Columns.Include("id"), new Condition.Permission(User.class, userId, Device.class)))) {
                accountDevices.add(device.getId());
                if (!owner.getAdministrator()) {
                    clientDevices.add(device.getId());
                }
            }
        }

        List<Action> actions = new ArrayList<>();
        if (wantActions) {
            loadActions(actions, from, to, userIds, deviceIds, actionTypes, clientDevices);
        }
        int eventCount = 0;
        if (wantEvents) {
            eventCount = loadEvents(actions, from, to, userIds, deviceIds, eventTypes, accountDevices);
            actions.sort(Comparator.comparing(Action::getActionTime));
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
        Map<String, String> driverNames = null;

        for (Action action : actions) {
            User user = users.get(action.getUserId());
            if (user != null) {
                action.setUserEmail(user.getEmail());
                action.setUserName(userLabel(user));
            }
            if (action.getObjectType() != null && action.getObjectId() > 0) {
                action.setObjectName(names.computeIfAbsent(action.getObjectType(), loadNames)
                        .get(action.getObjectId()));
                // Lo borrado ya no está: queda el nombre que se guardó al borrarlo.
                if (action.getObjectName() == null) {
                    action.setObjectName(action.getString("name"));
                }
            }
            String ownerType = action.getString("ownerType");
            if (ownerType != null) {
                action.setOwnerName(names.computeIfAbsent(ownerType, loadNames).get(action.getLong("ownerId")));
            }
            if (ACTION_EVENT.equals(action.getActionType())) {
                long geofenceId = action.getLong("geofenceId");
                if (geofenceId > 0) {
                    action.set("geofenceName", names.computeIfAbsent("geofence", loadNames).get(geofenceId));
                }
                long maintenanceId = action.getLong("maintenanceId");
                if (maintenanceId > 0) {
                    action.set("maintenanceName",
                            names.computeIfAbsent("maintenance", loadNames).get(maintenanceId));
                }
                String driverUniqueId = action.getString(Position.KEY_DRIVER_UNIQUE_ID);
                if (driverUniqueId != null) {
                    if (driverNames == null) {
                        driverNames = new HashMap<>();
                        for (Driver driver : storage.getObjects(Driver.class, new Request(new Columns.All()))) {
                            for (String form : driverIdForms(driver.getUniqueId())) {
                                driverNames.putIfAbsent(form, driver.getName());
                            }
                        }
                    }
                    for (String form : driverIdForms(driverUniqueId)) {
                        String driverName = driverNames.get(form);
                        if (driverName != null) {
                            action.set("driverName", driverName);
                            break;
                        }
                    }
                }
                // A quién se avisó (lo guarda NotificationManager): se agrega el nombre de cada cuenta.
                if (action.getAttributes().get("notified") instanceof List<?> deliveries) {
                    for (Object delivery : deliveries) {
                        if (delivery instanceof Map<?, ?> map && map.get("userId") instanceof Number userId) {
                            User recipient = users.get(userId.longValue());
                            @SuppressWarnings("unchecked")
                            Map<String, Object> values = (Map<String, Object>) map;
                            values.put("name", recipient != null ? userLabel(recipient) : "#" + userId);
                        }
                    }
                }
            }
        }

        CommandResultUtil.attach(storage, actions);
        Response.ResponseBuilder response = Response.ok(actions);
        if (eventCount > MAX_EVENTS) {
            response.header(EVENTS_TRUNCATED_HEADER, MAX_EVENTS);
        }
        return response.build();
    }

    private void loadActions(
            List<Action> actions, Date from, Date to, List<Long> userIds, List<Long> deviceIds,
            List<String> actionTypes, Set<Long> clientDevices) throws StorageException {
        try (var stream = storage.getObjectsStream(Action.class, new Request(
                new Columns.All(),
                new Condition.Between("actionTime", from, to),
                new Order("actionTime")))) {
            stream.filter(action -> actionTypes.isEmpty() || actionTypes.contains(action.getActionType()))
                    .filter(action -> {
                        if (userIds.isEmpty() || userIds.contains(action.getUserId())
                                || userIds.contains(action.getLong("actorUserId"))) {
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
    }

    // Devuelve cuántos eventos había (hasta MAX_EVENTS + 1); agrega a la lista solo los primeros MAX_EVENTS.
    // Con pocos carros elegidos (o los de las cuentas elegidas) se consulta carro por carro: tc_events está
    // particionada e indexada por carro y hora. Con muchos (una cuenta administradora ve casi todos) sale
    // mejor una sola consulta por fecha, quedándose con los de esos carros.
    private int loadEvents(
            List<Action> actions, Date from, Date to, List<Long> userIds, List<Long> deviceIds,
            List<String> eventTypes, Set<Long> accountDevices) throws StorageException {
        Set<Long> devices = null;
        if (!userIds.isEmpty()) {
            devices = new HashSet<>(accountDevices);
            if (!deviceIds.isEmpty()) {
                devices.retainAll(deviceIds);
            }
        } else if (!deviceIds.isEmpty()) {
            devices = new HashSet<>(deviceIds);
        }

        List<Condition> conditions = new ArrayList<>();
        Set<Long> deviceFilter = null;
        if (devices == null || devices.size() > MAX_DEVICE_QUERIES) {
            conditions.add(new Condition.Between("eventTime", from, to));
            deviceFilter = devices;
        } else {
            for (long deviceId : devices) {
                conditions.add(new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("eventTime", from, to)));
            }
        }

        int count = 0;
        for (Condition condition : conditions) {
            try (var stream = storage.getObjectsStream(Event.class, new Request(
                    new Columns.All(), condition, new Order("eventTime")))) {
                Set<Long> onlyDevices = deviceFilter;
                var iterator = stream
                        .filter(event -> onlyDevices == null || onlyDevices.contains(event.getDeviceId()))
                        .filter(event -> matchesEventType(event, eventTypes)).iterator();
                while (iterator.hasNext() && count <= MAX_EVENTS) {
                    Event event = iterator.next();
                    count += 1;
                    if (count <= MAX_EVENTS) {
                        actions.add(eventAction(event));
                    }
                }
            }
            if (count > MAX_EVENTS) {
                break;
            }
        }
        return count;
    }

    private static boolean matchesEventType(Event event, List<String> eventTypes) {
        if (eventTypes.isEmpty() || eventTypes.contains(event.getType())) {
            return true;
        }
        return Event.TYPE_ALARM.equals(event.getType())
                && eventTypes.contains(Event.TYPE_ALARM + ":" + event.getString(Position.KEY_ALARM));
    }

    // Id negativo: no choca con los de tc_actions en la misma lista.
    private static Action eventAction(Event event) {
        Action action = new Action();
        action.setId(-event.getId());
        action.setActionType(ACTION_EVENT);
        action.setActionTime(event.getEventTime());
        action.setObjectType("device");
        action.setObjectId(event.getDeviceId());
        action.getAttributes().putAll(event.getAttributes());
        action.set("eventType", event.getType());
        if (event.getGeofenceId() > 0) {
            action.set("geofenceId", event.getGeofenceId());
        }
        if (event.getMaintenanceId() > 0) {
            action.set("maintenanceId", event.getMaintenanceId());
        }
        if (event.getPositionId() > 0) {
            action.set("positionId", event.getPositionId());
        }
        return action;
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

    // Igual que DriverValue.js en la web: el lector manda el código como lo lee (a veces en hexadecimal,
    // con ceros o en minúsculas) y el conductor se carga a mano, quizás en decimal. Primero la forma exacta.
    private static List<String> driverIdForms(String value) {
        String id = value != null ? value.replaceAll("\\s+", "").toUpperCase().replaceFirst("^0+(?=.)", "") : "";
        List<String> forms = new ArrayList<>(List.of(id));
        try {
            if (id.matches("[0-9A-F]{1,12}")) {
                forms.add(String.valueOf(Long.parseLong(id, 16)));
            }
            if (id.matches("[0-9]{1,15}")) {
                forms.add(Long.toHexString(Long.parseLong(id)).toUpperCase());
            }
        } catch (NumberFormatException e) {
            return forms;
        }
        return forms;
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
        } else if (item instanceof Notification notification) {
            return notification.getDescription() != null ? notification.getDescription() : notification.getType();
        } else if (item instanceof Command command) {
            return command.getDescription();
        }
        return null;
    }

}
