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
import org.traccar.helper.model.CommandResultUtil;
import org.traccar.model.Action;
import org.traccar.model.BaseModel;
import org.traccar.model.Calendar;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Driver;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.Maintenance;
import org.traccar.model.Notification;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
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
                // Lo borrado ya no está: queda el nombre que se guardó al borrarlo.
                if (action.getObjectName() == null) {
                    action.setObjectName(action.getString("name"));
                }
            }
            String ownerType = action.getString("ownerType");
            if (ownerType != null) {
                action.setOwnerName(names.computeIfAbsent(ownerType, loadNames).get(action.getLong("ownerId")));
            }
        }

        CommandResultUtil.attach(storage, actions);
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
        } else if (item instanceof Notification notification) {
            return notification.getDescription() != null ? notification.getDescription() : notification.getType();
        } else if (item instanceof Command command) {
            return command.getDescription();
        }
        return null;
    }

}
