/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;
import org.traccar.api.BaseObjectResource;
import org.traccar.database.CommandsManager;
import org.traccar.database.MediaManager;
import org.traccar.helper.LogAction;
import org.traccar.helper.UnitsConverter;
import org.traccar.helper.model.CommandResultUtil;
import org.traccar.model.Action;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.DeviceAccumulators;
import org.traccar.model.ObjectOperation;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Path("devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceResource extends BaseObjectResource<Device> {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int IMAGE_SIZE_LIMIT = 500000;

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private CommandsManager commandsManager;

    @Inject
    private MediaManager mediaManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    public DeviceResource() {
        super(Device.class);
    }

    @GET
    public Stream<Device> get(
            @QueryParam("all") boolean all, @QueryParam("userId") long userId,
            @QueryParam("uniqueId") List<String> uniqueIds,
            @QueryParam("id") List<Long> deviceIds,
            @QueryParam("excludeAttributes") boolean excludeAttributes,
            @QueryParam("limit") int limit, @QueryParam("offset") int offset,
            @QueryParam("keyword") String keyword) throws StorageException {

        Columns columns = excludeAttributes ? new Columns.Exclude("attributes") : new Columns.All();

        if (!uniqueIds.isEmpty() || !deviceIds.isEmpty()) {

            List<Device> result = new LinkedList<>();
            for (String uniqueId : uniqueIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("uniqueId", uniqueId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            for (Long deviceId : deviceIds) {
                result.addAll(storage.getObjects(Device.class, new Request(
                        columns,
                        new Condition.And(
                                new Condition.Equals("id", deviceId),
                                new Condition.Permission(User.class, getUserId(), Device.class)))));
            }
            return result.stream();

        } else {

            var conditions = new LinkedList<Condition>();

            if (all) {
                if (permissionsService.notAdmin(getUserId())) {
                    conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                }
            } else {
                if (userId == 0) {
                    conditions.add(new Condition.Permission(User.class, getUserId(), baseClass));
                } else {
                    permissionsService.checkUser(getUserId(), userId);
                    conditions.add(new Condition.Permission(User.class, userId, baseClass).excludeGroups());
                }
            }

            if (keyword != null && !keyword.isEmpty()) {
                conditions.add(new Condition.Contains(
                        List.of("name", "uniqueId", "phone", "model", "contact"), keyword));
            }

            return storage.getObjectsStream(baseClass, new Request(
                    columns, Condition.merge(conditions), new Order("name", false, limit, offset)));

        }
    }

    // Usuarios (clientes) de cada carro, para que la lista se pueda filtrar o buscar por el
    // cliente dueño. Se omiten los administradores: tienen todos los carros.
    // Solo para un administrador que ve (prácticamente) todos los carros: el principal u otro igual.
    // Las cuentas administradoras del personal que ven una parte reciben la lista vacía y no tienen
    // el filtro. Es 90% y no 100% porque hay carros vinculados solo a la cuenta de su cliente (p. ej.
    // los que el cliente agregó él mismo) y uno solo de ésos le quitaba el filtro al principal.
    // Es orden, no seguridad: un administrador igual puede consultar los usuarios por la API.
    @Path("owners")
    @GET
    public Map<Long, List<Map<String, Object>>> getOwners() throws StorageException {
        permissionsService.checkAdmin(getUserId());
        int total = storage.getObjects(Device.class, new Request(new Columns.Include("id"))).size();
        int visible = storage.getObjects(Device.class, new Request(
                new Columns.Include("id"), new Condition.Permission(User.class, getUserId(), Device.class))).size();
        if (visible * 10 < total * 9) {
            return Map.of();
        }
        Map<Long, User> users = new HashMap<>();
        for (User user : storage.getObjects(User.class, new Request(new Columns.All()))) {
            if (!user.getAdministrator()) {
                users.put(user.getId(), user);
            }
        }
        Map<Long, List<Map<String, Object>>> result = new HashMap<>();
        for (var permission : storage.getPermissions(User.class, Device.class)) {
            User user = users.get(permission.getOwnerId());
            if (user != null) {
                Map<String, Object> owner = new LinkedHashMap<>();
                owner.put("id", user.getId());
                owner.put("name", user.getName());
                owner.put("email", user.getEmail());
                result.computeIfAbsent(permission.getPropertyId(), k -> new ArrayList<>()).add(owner);
            }
        }
        return result;
    }

    // Entre dos cambios de límite del mismo carro: el que aprieta varias veces no le manda al GPS el mismo
    // comando una y otra vez, y el equipo alcanza a contestar el anterior.
    private static final long SPEED_LIMIT_COOLDOWN = 30_000;
    // Una posición más vieja que esto no dice cómo va el carro ahora.
    private static final long SPEED_LIMIT_POSITION_AGE = 10 * 60 * 1000L;
    private static final long SPEED_LIMIT_HISTORY = 180L * 24 * 60 * 60 * 1000;
    private static final int SPEED_LIMIT_HISTORY_SIZE = 50;
    // Un comando y su cambio de límite se guardan uno tras otro: así se encuentran después.
    private static final long SPEED_LIMIT_COMMAND_MATCH = 10_000;
    private static final String SPEED_LIMIT_DESCRIPTION = "Límite de velocidad ";
    private static final Map<Long, Object> SPEED_LIMIT_LOCKS = new ConcurrentHashMap<>();

    // Cambia el límite de un carro y, si el GPS tiene limitador, le manda el comando. Lo usan el cliente
    // (con speedLimitEnabled) y el administrador. Responde 409/429 con {error, …} cuando no corresponde:
    //   cooldown   — hace menos de 30 s que se cambió el de este carro (seconds: cuánto falta);
    //   unchanged  — ya tiene ese límite; un administrador puede reenviarlo al GPS con resend=true;
    //   overspeed  — el carro va ahora por encima del límite actual: cambiarlo en pleno corte dejó trabada
    //                la salida en la Pathfinder y la Escape (oct 2026). El cliente no puede; un
    //                administrador puede insistir con force=true.
    @Path("{id}/speedlimit")
    @PUT
    public Response updateSpeedLimit(
            @PathParam("id") long id,
            @QueryParam("speed") double speedKmh,
            @QueryParam("resend") boolean resend,
            @QueryParam("force") boolean force) throws Exception {

        // Sin límites, un valor negativo/cero/NaN/absurdamente alto (ej. Double.MAX_VALUE)
        // se guardaría tal cual en el atributo del dispositivo y se inyectaría sin más
        // chequeo en el comando de hardware más abajo (commandTemplate.replace("{speed}", ...)).
        // 300 km/h cubre cualquier vehículo real rastreado por este sistema.
        if (!Double.isFinite(speedKmh) || speedKmh <= 0 || speedKmh > 300) {
            throw new IllegalArgumentException("speed must be between 0 and 300 km/h");
        }

        // Verificar acceso al dispositivo (lectura mínima)
        permissionsService.checkPermission(Device.class, getUserId(), id);

        boolean admin = !permissionsService.notAdmin(getUserId());
        if (!admin) {
            User user = storage.getObject(User.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("id", getUserId())));
            if (user == null || !Boolean.TRUE.equals(user.getAttributes().get("speedLimitEnabled"))) {
                throw new SecurityException("Speed limit service not enabled for this user");
            }
        }

        // Dos pedidos al mismo carro a la vez (doble clic, dos pestañas) se atienden de a uno, y el segundo
        // ya ve la espera del primero.
        synchronized (SPEED_LIMIT_LOCKS.computeIfAbsent(id, key -> new Object())) {
            return applySpeedLimit(id, (int) Math.round(speedKmh), admin, admin && resend, admin && force);
        }
    }

    private Response applySpeedLimit(
            long id, int speed, boolean admin, boolean resend, boolean force) throws Exception {

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", id),
                        new Condition.Permission(User.class, getUserId(), Device.class))));
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        long remaining = speedLimitCooldown(id);
        if (remaining > 0) {
            return speedLimitRejected(429, "cooldown", Map.of("seconds", (remaining + 999) / 1000));
        }

        Double previous = knots(device.getAttributes().get(Position.KEY_SPEED_LIMIT));
        Integer previousKmh = previous != null ? (int) Math.round(UnitsConverter.kphFromKnots(previous)) : null;
        String commandTemplate = (String) device.getAttributes().get("speedLimitCommand");
        boolean hardware = Boolean.TRUE.equals(device.getAttributes().get("speedLimitSupported"))
                && commandTemplate != null && !commandTemplate.isEmpty();

        boolean same = previousKmh != null && previousKmh == speed;
        if (same && !(resend && hardware)) {
            return speedLimitRejected(409, "unchanged", Map.of("speed", speed, "canResend", admin && hardware));
        }

        boolean forced = false;
        if (hardware && previous != null) {
            Position position = storage.getObject(Position.class, new Request(
                    new Columns.All(), new Condition.LatestPositions(id)));
            if (position != null && position.getServerTime() != null
                    && System.currentTimeMillis() - position.getServerTime().getTime() < SPEED_LIMIT_POSITION_AGE
                    && position.getSpeed() > previous) {
                if (!force) {
                    return speedLimitRejected(409, "overspeed", Map.of(
                            "speed", Math.round(UnitsConverter.kphFromKnots(position.getSpeed())),
                            "limit", previousKmh,
                            "canForce", admin));
                }
                forced = true;
            }
        }

        double speedKnots = UnitsConverter.knotsFromKph(speed);
        if (!same) {
            device.getAttributes().put(Position.KEY_SPEED_LIMIT, speedKnots);
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"),
                    new Condition.Equals("id", id)));
            // Sin esto el aviso de exceso del servidor seguía usando el límite viejo hasta recargar.
            cacheManager.invalidateObject(true, Device.class, id, ObjectOperation.UPDATE);
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        if (same) {
            extra.put("resend", true);
        }
        if (forced) {
            extra.put("forced", true);
        }
        String status = "none";
        String error = null;
        if (hardware) {
            // El template lo define el admin en el dispositivo; el cliente solo aporta el número.
            Command command = new Command();
            command.setDeviceId(id);
            command.setType(Command.TYPE_CUSTOM);
            command.getAttributes().put(Command.KEY_DATA, commandTemplate.replace("{speed}", String.valueOf(speed)));
            String description = SPEED_LIMIT_DESCRIPTION + speed + " km/h";
            long queuedCommandId = 0;
            try {
                var queuedCommand = commandsManager.sendCommand(command);
                status = queuedCommand != null ? LogAction.COMMAND_QUEUED : LogAction.COMMAND_SENT;
                queuedCommandId = queuedCommand != null ? queuedCommand.getId() : 0;
            } catch (Exception e) {
                status = LogAction.COMMAND_FAILED;
                error = e.getMessage();
            }
            actionLogger.command(request, getUserId(), 0, command, description, status, queuedCommandId, error);
            extra.put("hardware", true);
            extra.put("commandStatus", status);
        }
        actionLogger.speedLimit(request, getUserId(), id, previous, speedKnots, extra);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("speed", speed);
        result.put("commandStatus", status);
        if (error != null) {
            result.put("error", error);
        }
        result.put("cooldown", SPEED_LIMIT_COOLDOWN / 1000);
        return Response.ok(result).build();
    }

    private long speedLimitCooldown(long deviceId) throws StorageException {
        long now = System.currentTimeMillis();
        long last = 0;
        for (Action action : storage.getObjects(Action.class, new Request(
                new Columns.Include("actionTime"),
                Condition.merge(List.of(
                        new Condition.Equals("actionType", "speedLimit"),
                        new Condition.Equals("objectType", "device"),
                        new Condition.Equals("objectId", deviceId),
                        new Condition.Compare("actionTime", ">", new Date(now - SPEED_LIMIT_COOLDOWN))))))) {
            last = Math.max(last, action.getActionTime().getTime());
        }
        return last > 0 ? Math.max(0, last + SPEED_LIMIT_COOLDOWN - now) : 0;
    }

    private static Response speedLimitRejected(int status, String error, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.putAll(details);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static Double knots(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        } else if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // Último cambio de límite de cada carro que ve la cuenta, y cuánto falta para poder volver a cambiarlo.
    @Path("speedlimit/last")
    @GET
    public Map<Long, Map<String, Object>> getSpeedLimitLast() throws StorageException {
        boolean admin = !permissionsService.notAdmin(getUserId());
        Set<Long> visible = new HashSet<>();
        for (Device device : storage.getObjects(Device.class, new Request(
                new Columns.Include("id"), new Condition.Permission(User.class, getUserId(), Device.class)))) {
            visible.add(device.getId());
        }
        long now = System.currentTimeMillis();
        Map<Long, Action> last = new HashMap<>();
        for (Action action : storage.getObjects(Action.class, new Request(
                new Columns.All(),
                Condition.merge(List.of(
                        new Condition.Equals("actionType", "speedLimit"),
                        new Condition.Equals("objectType", "device"),
                        new Condition.Compare("actionTime", ">", new Date(now - SPEED_LIMIT_HISTORY)))),
                new Order("actionTime")))) {
            if (admin || visible.contains(action.getObjectId())) {
                last.put(action.getObjectId(), action);
            }
        }
        Map<Long, User> users = new HashMap<>();
        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (Action action : last.values()) {
            Map<String, Object> entry = speedLimitEntry(action, admin, users);
            long cooldown = action.getActionTime().getTime() + SPEED_LIMIT_COOLDOWN - now;
            entry.put("cooldown", Math.max(0, (cooldown + 999) / 1000));
            result.put(action.getObjectId(), entry);
        }
        return result;
    }

    // Historial de cambios de límite de un carro (los últimos 180 días), con lo que pasó en el GPS.
    @Path("{id}/speedlimit/history")
    @GET
    public List<Map<String, Object>> getSpeedLimitHistory(@PathParam("id") long id) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), id);
        boolean admin = !permissionsService.notAdmin(getUserId());
        Date to = new Date();
        Date from = new Date(to.getTime() - SPEED_LIMIT_HISTORY);

        List<Action> limits = new ArrayList<>();
        List<Action> commands = new ArrayList<>();
        for (Action action : storage.getObjects(Action.class, new Request(
                new Columns.All(),
                Condition.merge(List.of(
                        new Condition.Equals("objectType", "device"),
                        new Condition.Equals("objectId", id),
                        new Condition.Between("actionTime", from, to),
                        new Condition.Or(
                                new Condition.Equals("actionType", "speedLimit"),
                                new Condition.Equals("actionType", "command")))),
                new Order("actionTime")))) {
            if ("speedLimit".equals(action.getActionType())) {
                limits.add(action);
            } else {
                commands.add(action);
            }
        }
        CommandResultUtil.attach(storage, commands);

        Map<Long, User> users = new HashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = limits.size() - 1; i >= 0 && result.size() < SPEED_LIMIT_HISTORY_SIZE; i--) {
            Action action = limits.get(i);
            Map<String, Object> entry = speedLimitEntry(action, admin, users);
            if (action.getBoolean("hardware")) {
                Action command = matchingCommand(action, commands);
                if (command != null) {
                    entry.put("commandStatus", command.getString("status"));
                    entry.put("commandError", command.getString("error"));
                    entry.put("queuedSentTime", command.getQueuedSentTime());
                    entry.put("result", command.getCommandResult());
                    entry.put("resultTime", command.getCommandResultTime());
                }
            }
            result.add(entry);
        }
        return result;
    }

    private static Action matchingCommand(Action limit, List<Action> commands) {
        long time = limit.getActionTime().getTime();
        Action best = null;
        for (Action command : commands) {
            String description = command.getString("commandDescription");
            long distance = Math.abs(command.getActionTime().getTime() - time);
            if (command.getUserId() == limit.getUserId() && distance <= SPEED_LIMIT_COMMAND_MATCH
                    && description != null && description.startsWith(SPEED_LIMIT_DESCRIPTION)
                    && (best == null || distance < Math.abs(best.getActionTime().getTime() - time))) {
                best = command;
            }
        }
        return best;
    }

    // Al cliente no se le dice qué persona de Telcon lo cambió: solo que fue la administración.
    private Map<String, Object> speedLimitEntry(Action action, boolean admin, Map<Long, User> users)
            throws StorageException {
        User user = users.get(action.getUserId());
        if (user == null && action.getUserId() > 0 && !users.containsKey(action.getUserId())) {
            user = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", action.getUserId())));
            users.put(action.getUserId(), user);
        }
        boolean byAdmin = user != null && user.getAdministrator();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", action.getActionTime());
        entry.put("from", action.getAttributes().get("from"));
        entry.put("to", action.getAttributes().get("to"));
        entry.put("byAdmin", byAdmin);
        entry.put("bySelf", action.getUserId() == getUserId());
        if (user != null && (admin || !byAdmin)) {
            entry.put("by", user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getEmail());
        }
        if (admin) {
            entry.put("actor", action.getString("actor"));
        }
        entry.put("hardware", action.getBoolean("hardware"));
        entry.put("commandStatus", action.getString("commandStatus"));
        entry.put("resend", action.getBoolean("resend"));
        entry.put("forced", action.getBoolean("forced"));
        return entry;
    }

    @Path("{id}/accumulators")
    @PUT
    public Response updateAccumulators(DeviceAccumulators entity) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
        permissionsService.checkEdit(getUserId(), Device.class, false, false);

        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(entity.getDeviceId())));
        Double previousDistance = null;
        Long previousHours = null;
        if (position != null) {
            if (position.hasAttribute(Position.KEY_TOTAL_DISTANCE)) {
                previousDistance = position.getDouble(Position.KEY_TOTAL_DISTANCE);
            }
            if (position.hasAttribute(Position.KEY_HOURS)) {
                previousHours = position.getLong(Position.KEY_HOURS);
            }
            if (entity.getTotalDistance() != null) {
                position.getAttributes().put(Position.KEY_TOTAL_DISTANCE, entity.getTotalDistance());
            }
            if (entity.getHours() != null) {
                position.getAttributes().put(Position.KEY_HOURS, entity.getHours());
            }
            position.setId(storage.addObject(position, new Request(new Columns.Exclude("id"))));

            Device device = new Device();
            device.setId(position.getDeviceId());
            device.setPositionId(position.getId());
            storage.updateObject(device, new Request(
                    new Columns.Include("positionId"),
                    new Condition.Equals("id", device.getId())));

            var key = new Object();
            try {
                cacheManager.addDevice(position.getDeviceId(), key);
                cacheManager.updatePosition(position);
                connectionManager.updatePosition(true, position);
            } finally {
                cacheManager.removeDevice(position.getDeviceId(), key);
            }
        } else {
            throw new IllegalArgumentException();
        }

        actionLogger.resetAccumulators(request, getUserId(), entity.getDeviceId(),
                previousDistance, previousHours, entity.getTotalDistance(), entity.getHours());
        return Response.noContent().build();
    }

    private String imageExtension(String type) {
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("Unsupported image type");
        };
    }

    @Path("{id}/image")
    @POST
    @Consumes("image/*")
    public Response uploadImage(
            @PathParam("id") long deviceId, File file,
            @HeaderParam(HttpHeaders.CONTENT_TYPE) String type) throws StorageException, IOException {

        Device device = storage.getObject(Device.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("id", deviceId),
                        new Condition.Permission(User.class, getUserId(), Device.class))));
        if (device != null) {
            String name = "device";
            String extension = imageExtension(type);
            try (var input = new FileInputStream(file);
                    var output = mediaManager.createFileStream(device.getUniqueId(), name, extension)) {

                long transferred = 0;
                byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer, 0, buffer.length)) >= 0) {
                    output.write(buffer, 0, read);
                    transferred += read;
                    if (transferred > IMAGE_SIZE_LIMIT) {
                        throw new IllegalArgumentException("Image size limit exceeded");
                    }
                }
            }
            return Response.ok(name + "." + extension).build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

}
