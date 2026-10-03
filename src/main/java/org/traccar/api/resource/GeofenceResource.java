/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.traccar.api.ExtendedObjectResource;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("geofences")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GeofenceResource extends ExtendedObjectResource<Geofence> {

    @Inject
    private CacheManager cacheManager;

    public GeofenceResource() {
        super(Geofence.class, "name", List.of("name"));
    }

    // La que crea un cliente desde su cuenta no avisa a administración. La que crea un
    // administrador sí (es de Telcon); al pasarla a un cliente el formulario apaga el aviso.
    // El agregado automático se prende después, desde el panel de asignaciones.
    @Override
    @POST
    public Response add(Geofence entity) throws Exception {
        if (permissionsService.notAdmin(getUserId())) {
            entity.getAttributes().put(Geofence.KEY_NOTIFY_ADMINISTRATORS, false);
        }
        entity.getAttributes().remove(Geofence.KEY_AUTO_USERS);
        entity.getAttributes().put(Geofence.KEY_CREATED_BY, getUserId());
        return super.add(entity);
    }

    // "creadoPor" lo fija el servidor; el aviso a administración y el agregado automático
    // solo los cambia un administrador. Lo demás se guarda como siempre.
    @Override
    @Path("{id}")
    @PUT
    public Response update(Geofence entity) throws Exception {
        permissionsService.checkPermission(Geofence.class, getUserId(), entity.getId());
        Geofence stored = storage.getObject(Geofence.class, new Request(
                new Columns.All(), new Condition.Equals("id", entity.getId())));
        if (stored != null) {
            List<String> protectedKeys = new ArrayList<>(List.of(Geofence.KEY_CREATED_BY));
            if (permissionsService.notAdmin(getUserId())) {
                protectedKeys.add(Geofence.KEY_NOTIFY_ADMINISTRATORS);
                protectedKeys.add(Geofence.KEY_AUTO_USERS);
            }
            for (String key : protectedKeys) {
                if (stored.hasAttribute(key)) {
                    entity.getAttributes().put(key, stored.getAttributes().get(key));
                } else {
                    entity.getAttributes().remove(key);
                }
            }
        }
        return super.update(entity);
    }

    // Todo lo necesario para responder "¿a quién y a qué carros está asignada cada geocerca?"
    // en una sola llamada. Solo administradores: expone usuarios y carros de todos los clientes.
    @Path("assignments")
    @GET
    public Map<String, Object> getAssignments() throws StorageException {
        permissionsService.checkAdmin(getUserId());

        List<Map<String, Object>> users = new ArrayList<>();
        for (User user : storage.getObjects(User.class, new Request(new Columns.All()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("name", user.getName());
            item.put("email", user.getEmail());
            item.put("administrator", user.getAdministrator());
            item.put("disabled", user.getDisabled());
            users.add(item);
        }

        List<Map<String, Object>> devices = new ArrayList<>();
        for (Device device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", device.getId());
            item.put("name", device.getName());
            item.put("uniqueId", device.getUniqueId());
            item.put("groupId", device.getGroupId());
            devices.add(item);
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Group group : storage.getObjects(Group.class, new Request(new Columns.All()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", group.getId());
            item.put("name", group.getName());
            item.put("groupId", group.getGroupId());
            groups.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users);
        result.put("devices", devices);
        result.put("groups", groups);
        result.put("userDevices", byOwner(storage.getPermissions(User.class, Device.class)));
        result.put("geofenceUsers", byProperty(storage.getPermissions(User.class, Geofence.class)));
        result.put("geofenceDevices", byProperty(storage.getPermissions(Device.class, Geofence.class)));
        result.put("geofenceGroups", byProperty(storage.getPermissions(Group.class, Geofence.class)));
        return result;
    }

    private static Map<Long, Set<Long>> byOwner(List<Permission> permissions) {
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Permission permission : permissions) {
            result.computeIfAbsent(permission.getOwnerId(), k -> new LinkedHashSet<>())
                    .add(permission.getPropertyId());
        }
        return result;
    }

    private static Map<Long, Set<Long>> byProperty(List<Permission> permissions) {
        Map<Long, Set<Long>> result = new HashMap<>();
        for (Permission permission : permissions) {
            result.computeIfAbsent(permission.getPropertyId(), k -> new LinkedHashSet<>())
                    .add(permission.getOwnerId());
        }
        return result;
    }

    // Prende o apaga "agregar sola a los carros nuevos de este usuario". No toca los carros
    // que el usuario ya tiene: esos se agregan a mano desde el panel de asignaciones.
    @Path("{id}/auto")
    @PUT
    public Geofence updateAutoAssign(
            @PathParam("id") long id,
            @QueryParam("userId") long userId,
            @QueryParam("enabled") boolean enabled) throws Exception {
        permissionsService.checkAdmin(getUserId());
        Geofence geofence = storage.getObject(Geofence.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
        if (geofence == null || userId <= 0) {
            throw new IllegalArgumentException("Geocerca o usuario inválido");
        }
        Set<String> autoUsers = new LinkedHashSet<>();
        String current = geofence.getString(Geofence.KEY_AUTO_USERS);
        if (current != null && !current.isEmpty()) {
            autoUsers.addAll(Arrays.asList(current.split(",")));
        }
        if (enabled) {
            autoUsers.add(String.valueOf(userId));
        } else {
            autoUsers.remove(String.valueOf(userId));
        }
        if (autoUsers.isEmpty()) {
            geofence.getAttributes().remove(Geofence.KEY_AUTO_USERS);
        } else {
            geofence.getAttributes().put(Geofence.KEY_AUTO_USERS, String.join(",", autoUsers));
        }
        storage.updateObject(geofence, new Request(
                new Columns.Include("attributes"), new Condition.Equals("id", id)));
        cacheManager.invalidateObject(true, Geofence.class, id, ObjectOperation.UPDATE);
        return geofence;
    }

}
