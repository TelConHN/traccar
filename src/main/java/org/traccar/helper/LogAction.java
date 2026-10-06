/*
 * Copyright 2017 - 2025 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.helper;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Action;
import org.traccar.model.BaseModel;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.storage.QueryIgnore;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

public final class LogAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogAction.class);

    private final Storage storage;
    private final ObjectMapper objectMapper;

    @Inject
    public LogAction(Storage storage, ObjectMapper objectMapper) {
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    private static final String ACTION_CREATE = "create";
    private static final String ACTION_EDIT = "edit";
    private static final String ACTION_REMOVE = "remove";

    private static final String ACTION_LINK = "link";
    private static final String ACTION_UNLINK = "unlink";

    private static final String ACTION_LOGIN = "login";
    private static final String ACTION_LOGOUT = "logout";
    private static final String ACTION_DENIED = "denied";

    private static final String ACTION_ACCUMULATORS = "accumulators";
    private static final String ACTION_COMMAND = "command";
    private static final String ACTION_REPORT = "report";

    private static final String ACTION_SPEED_LIMIT = "speedLimit";
    private static final String ACTION_REMOVE_POSITIONS = "removePositions";
    private static final String ACTION_SHARE = "share";
    private static final String ACTION_PASSWORD_RESET = "passwordReset";
    private static final String ACTION_PASSWORD_UPDATE = "passwordUpdate";
    private static final String ACTION_MESSAGE = "message";
    private static final String ACTION_SERVER = "server";

    // El panel admin y Rutas/Transporte le hablan a Traccar con la cuenta técnica del panel; sin esto todo lo que
    // hacen queda a nombre de esa cuenta. Mandan en estos encabezados quién lo pidió (texto en URL encoding).
    private static final String ACTOR_HEADER = "X-Audit-Actor";
    private static final String ACTOR_USER_HEADER = "X-Audit-Actor-User";

    // tc_actions.attributes es VARCHAR(4000): un registro más largo no se guarda.
    private static final int MAX_ATTRIBUTES_LENGTH = 3800;
    private static final int MAX_VALUE_LENGTH = 150;
    private static final String HIDDEN = "(oculto)";

    // Además de éstos se saltan los que no se guardan (@QueryIgnore): los arma Traccar al responder, como el
    // estado de conexión de un carro, y el que los compara ve "cambios" que nadie hizo.
    private static final Set<String> IGNORED_FIELDS = Set.of("id", "attributes", "password");
    private static final Map<Class<?>, Set<String>> COMPUTED_FIELDS = new ConcurrentHashMap<>();
    private static final Set<String> IGNORED_ATTRIBUTES = Set.of("notificationTokens");

    public void create(HttpServletRequest request, long userId, BaseModel object) {
        logObjectAction(request, ACTION_CREATE, userId, object.getClass(), object.getId());
    }

    // Guarda qué campos cambiaron (antes → después). Sin "before" queda como antes: solo qué objeto se editó.
    // Un cambio de límite de velocidad de un carro va además en su propio registro, para poder filtrarlo.
    public void edit(HttpServletRequest request, long userId, BaseModel before, BaseModel after) {
        List<Map<String, Object>> changes = before != null ? changes(before, after) : null;
        if (changes != null && before instanceof Device && after instanceof Device) {
            String speedLimitField = "attributes." + Position.KEY_SPEED_LIMIT;
            if (changes.removeIf(change -> speedLimitField.equals(change.get("field")))) {
                speedLimit(request, userId, after.getId(),
                        speedLimit((Device) before), speedLimit((Device) after), null);
                if (changes.isEmpty()) {
                    return;
                }
            }
        }
        Action action = newAction(request, userId, ACTION_EDIT);
        action.setObjectType(Introspector.decapitalize(after.getClass().getSimpleName()));
        action.setObjectId(after.getId());
        if (changes != null) {
            if (changes.isEmpty()) {
                action.set("unchanged", true);
            } else {
                putChanges(action, changes);
            }
        }
        storeAction(action);
    }

    // Se guarda el nombre: después de borrarlo el reporte ya no lo puede buscar.
    public void remove(HttpServletRequest request, long userId, Class<?> clazz, long objectId, BaseModel object) {
        Action action = newAction(request, userId, ACTION_REMOVE);
        action.setObjectType(Introspector.decapitalize(clazz.getSimpleName()));
        action.setObjectId(objectId);
        if (object != null) {
            Map<String, Object> values = toMap(object);
            for (String key : List.of("name", "description", "email")) {
                Object value = values.get(key);
                if (value != null && !value.toString().isBlank()) {
                    action.set("name", StringUtils.abbreviate(value.toString(), MAX_VALUE_LENGTH));
                    break;
                }
            }
        }
        storeAction(action);
    }

    public void link(
            HttpServletRequest request, long userId, Class<?> owner, long ownerId, Class<?> property, long propertyId) {
        logLinkAction(request, ACTION_LINK, userId, owner, ownerId, property, propertyId);
    }

    public void unlink(
            HttpServletRequest request, long userId, Class<?> owner, long ownerId, Class<?> property, long propertyId) {
        logLinkAction(request, ACTION_UNLINK, userId, owner, ownerId, property, propertyId);
    }

    public void login(HttpServletRequest request, long userId) {
        logLoginAction(request, ACTION_LOGIN, userId);
    }

    public void logout(HttpServletRequest request, long userId) {
        logLoginAction(request, ACTION_LOGOUT, userId);
    }

    public void token(HttpServletRequest request, long userId, long tokenId) {
        Action action = newAction(request, userId, ACTION_CREATE);
        action.setObjectType("token");
        action.setObjectId(tokenId);
        storeAction(action);
    }

    public void tokenRevoke(HttpServletRequest request, long userId, long tokenId) {
        Action action = newAction(request, userId, ACTION_REMOVE);
        action.setObjectType("token");
        action.setObjectId(tokenId);
        storeAction(action);
    }

    // Con qué correo intentaron entrar: sin eso no se sabe a qué cuenta le estaban probando contraseñas.
    public void failedLogin(HttpServletRequest request, String email) {
        Action action = newAction(request, 0, ACTION_DENIED);
        if (StringUtils.isNotBlank(email)) {
            action.set("email", StringUtils.abbreviate(email.trim(), 100));
        }
        storeAction(action);

        LOGGER.info(String.format(
                "login failed from: %s",
                StringUtils.isEmpty(action.getAddress()) ? "unknown" : action.getAddress()));
    }

    public void resetAccumulators(
            HttpServletRequest request, long userId, long deviceId,
            Double previousDistance, Long previousHours, Double distance, Long hours) {
        Action action = newAction(request, userId, ACTION_ACCUMULATORS);
        action.setObjectType(Introspector.decapitalize(Device.class.getSimpleName()));
        action.setObjectId(deviceId);
        if (distance != null) {
            action.set("previousTotalDistance", previousDistance);
            action.set("totalDistance", distance);
        }
        if (hours != null) {
            action.set("previousHours", previousHours);
            action.set("hours", hours);
        }
        storeAction(action);
    }

    // Límites en nudos (como los guarda Traccar); se registran en km/h, que es como se escriben.
    // extra: lo que pasó con el envío al GPS (commandStatus, queuedCommandId, resend, forced…).
    public void speedLimit(
            HttpServletRequest request, long userId, long deviceId, Double from, Double to,
            Map<String, Object> extra) {
        Action action = newAction(request, userId, ACTION_SPEED_LIMIT);
        action.setObjectType(Introspector.decapitalize(Device.class.getSimpleName()));
        action.setObjectId(deviceId);
        if (from != null) {
            action.set("from", Math.round(UnitsConverter.kphFromKnots(from)));
        }
        if (to != null) {
            action.set("to", Math.round(UnitsConverter.kphFromKnots(to)));
        }
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (value != null) {
                    action.getAttributes().put(key, value);
                }
            });
        }
        storeAction(action);
    }

    public static final String COMMAND_SENT = "sent";
    public static final String COMMAND_QUEUED = "queued";
    public static final String COMMAND_FAILED = "failed";

    // Un registro por carro, también cuando el comando se mandó a un grupo (queda groupId), para que el
    // reporte de auditoría pueda decir a qué carro fue, filtrar por carro y buscar la respuesta del equipo.
    // Se guarda qué comando fue (tipo, nombre si era uno guardado y sus parámetros, como el texto de un
    // personalizado) y si salió, quedó en cola porque el equipo no estaba conectado, o falló.
    public void command(
            HttpServletRequest request, long userId, long groupId, Command command, String description,
            String status, long queuedCommandId, String error) {
        Action action = commandAction(request, userId, command, description, status, queuedCommandId, error);
        if (groupId > 0) {
            action.set("groupId", groupId);
        }
        storeAction(action);
    }

    // El que manda Traccar solo cuando salta una notificación con comando (p. ej. bloquear al salir de una
    // geocerca). Queda a nombre del dueño de la notificación, marcado como automático.
    public void automaticCommand(
            long userId, Notification notification, Event event, Command command,
            String status, long queuedCommandId, String error) {
        Action action = commandAction(
                null, userId, command, command.getDescription(), status, queuedCommandId, error);
        action.set("automatic", true);
        action.set("notificationId", notification.getId());
        if (StringUtils.isNotBlank(notification.getDescription())) {
            action.set("notificationDescription", StringUtils.abbreviate(notification.getDescription(), 100));
        }
        action.set("eventType", event.getType());
        storeAction(action);
    }

    private Action commandAction(
            HttpServletRequest request, long userId, Command command, String description,
            String status, long queuedCommandId, String error) {
        Action action = newAction(request, userId, ACTION_COMMAND);
        action.setObjectType(Introspector.decapitalize(Device.class.getSimpleName()));
        action.setObjectId(command.getDeviceId());
        action.set("commandType", command.getType());
        if (StringUtils.isNotBlank(description)) {
            action.set("commandDescription", description);
        }
        if (command.getTextChannel()) {
            action.set("sms", true);
        }
        command.getAttributes().forEach((key, value) -> {
            if (!Command.KEY_NO_QUEUE.equals(key) && value != null) {
                action.set("command." + key, StringUtils.abbreviate(value.toString(), 500));
            }
        });
        action.set("status", status);
        if (queuedCommandId > 0) {
            action.set("queuedCommandId", queuedCommandId);
        }
        if (error != null) {
            action.set("error", StringUtils.abbreviate(error, 300));
        }
        return action;
    }

    // Borrar posiciones borra historial: una sola (positionId) o un rango de fechas de un carro.
    public void removePositions(
            HttpServletRequest request, long userId, long deviceId, long positionId, Date from, Date to) {
        Action action = newAction(request, userId, ACTION_REMOVE_POSITIONS);
        action.setObjectType(Introspector.decapitalize(Device.class.getSimpleName()));
        action.setObjectId(deviceId);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        if (positionId > 0) {
            action.set("positionId", positionId);
        }
        if (from != null) {
            action.set("from", dateFormat.format(from));
        }
        if (to != null) {
            action.set("to", dateFormat.format(to));
        }
        storeAction(action);
    }

    // Enlace para ver un carro o grupo sin cuenta.
    public void share(HttpServletRequest request, long userId, Class<?> clazz, long objectId, Date expiration) {
        Action action = newAction(request, userId, ACTION_SHARE);
        action.setObjectType(Introspector.decapitalize(clazz.getSimpleName()));
        action.setObjectId(objectId);
        if (expiration != null) {
            action.set("expiration", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(expiration));
        }
        storeAction(action);
    }

    // Pedido de "olvidé mi contraseña": se registra aunque el correo no exista (userId 0).
    public void passwordReset(HttpServletRequest request, String email, long userId) {
        Action action = newAction(request, userId, ACTION_PASSWORD_RESET);
        if (StringUtils.isNotBlank(email)) {
            action.set("email", StringUtils.abbreviate(email.trim(), 100));
        }
        storeAction(action);
    }

    // Contraseña cambiada con el enlace del correo (la que cambia un usuario o un administrador va en "edit").
    public void passwordUpdate(HttpServletRequest request, long userId) {
        Action action = newAction(request, userId, ACTION_PASSWORD_UPDATE);
        action.setObjectType(Introspector.decapitalize(User.class.getSimpleName()));
        action.setObjectId(userId);
        storeAction(action);
    }

    public void message(
            HttpServletRequest request, long userId, String notificator, List<Long> userIds, int count,
            String subject) {
        Action action = newAction(request, userId, ACTION_MESSAGE);
        action.set("notificator", notificator);
        action.set("recipients", count);
        if (!userIds.isEmpty()) {
            action.set("users", StringUtils.abbreviate(userIds.toString(), 500));
        }
        if (StringUtils.isNotBlank(subject)) {
            action.set("subject", StringUtils.abbreviate(subject, 200));
        }
        storeAction(action);
    }

    // Operaciones sobre el servidor: reiniciar, liberar memoria, ver la caché, subir un archivo a la web.
    public void server(HttpServletRequest request, long userId, String operation, String detail) {
        Action action = newAction(request, userId, ACTION_SERVER);
        action.set("operation", operation);
        if (StringUtils.isNotBlank(detail)) {
            action.set("detail", StringUtils.abbreviate(detail, 200));
        }
        storeAction(action);
    }

    public void report(
            HttpServletRequest request, long userId, boolean scheduled, String report,
            Date from, Date to, List<Long> deviceIds, List<Long> groupIds) {
        Action action = newAction(request, userId, ACTION_REPORT);
        action.set("scheduled", scheduled ? true : null);
        action.set("type", report);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        action.set("from", dateFormat.format(from));
        action.set("to", dateFormat.format(to));
        action.set("devices", deviceIds.toString());
        action.set("groups", groupIds.toString());
        storeAction(action);
    }

    private void logObjectAction(
            HttpServletRequest request, String actionType, long userId, Class<?> clazz, long objectId) {
        Action action = newAction(request, userId, actionType);
        action.setObjectType(Introspector.decapitalize(clazz.getSimpleName()));
        action.setObjectId(objectId);
        storeAction(action);
    }

    private void logLinkAction(
            HttpServletRequest request, String actionType,
            long userId, Class<?> owner, long ownerId, Class<?> property, long propertyId) {
        Action action = newAction(request, userId, actionType);
        action.setObjectType(Introspector.decapitalize(property.getSimpleName()));
        action.setObjectId(propertyId);
        action.set("ownerType", Introspector.decapitalize(owner.getSimpleName()));
        action.set("ownerId", ownerId);
        storeAction(action);
    }

    private void logLoginAction(
            HttpServletRequest request, String actionType, long userId) {
        Action action = newAction(request, userId, actionType);
        storeAction(action);
    }

    private Action newAction(HttpServletRequest request, long userId, String actionType) {
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(actionType);
        setActor(request, userId, action);
        return action;
    }

    // Solo se cree el encabezado si la cuenta que llama es administradora: cualquier otra podría inventarlo.
    private void setActor(HttpServletRequest request, long userId, Action action) {
        String actor = request != null ? request.getHeader(ACTOR_HEADER) : null;
        if (StringUtils.isBlank(actor) || userId <= 0) {
            return;
        }
        try {
            User user = storage.getObject(User.class, new Request(
                    new Columns.Include("administrator"), new Condition.Equals("id", userId)));
            if (user == null || !user.getAdministrator()) {
                return;
            }
            action.set("actor", StringUtils.abbreviate(URLDecoder.decode(actor, StandardCharsets.UTF_8), 200));
        } catch (StorageException | IllegalArgumentException e) {
            return;
        }
        String actorUser = request.getHeader(ACTOR_USER_HEADER);
        if (actorUser != null && actorUser.matches("\\d{1,18}")) {
            action.set("actorUserId", Long.parseLong(actorUser));
        }
    }

    private static Double speedLimit(Device device) {
        Object value = device.getAttributes().get(Position.KEY_SPEED_LIMIT);
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

    private Map<String, Object> toMap(Object object) {
        return objectMapper.convertValue(object, new TypeReference<Map<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> changes(BaseModel before, BaseModel after) {
        Map<String, Object> beforeValues = toMap(before);
        Map<String, Object> afterValues = toMap(after);
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> ignored = new HashSet<>(IGNORED_FIELDS);
        ignored.addAll(computedFields(after.getClass()));
        compare(changes, "", beforeValues, afterValues, ignored);
        Object beforeAttributes = beforeValues.get("attributes");
        Object afterAttributes = afterValues.get("attributes");
        compare(changes, "attributes.",
                beforeAttributes instanceof Map ? (Map<String, Object>) beforeAttributes : Map.of(),
                afterAttributes instanceof Map ? (Map<String, Object>) afterAttributes : Map.of(),
                IGNORED_ATTRIBUTES);
        if (after instanceof User user && user.getHashedPassword() != null) {
            changes.add(change("password", null, HIDDEN));
        }
        return changes;
    }

    private static Set<String> computedFields(Class<?> clazz) {
        return COMPUTED_FIELDS.computeIfAbsent(clazz, key -> {
            Set<String> fields = new HashSet<>();
            for (Method method : key.getMethods()) {
                if (method.isAnnotationPresent(QueryIgnore.class) && method.getParameterCount() == 0) {
                    String name = method.getName();
                    if (name.startsWith("get") && name.length() > 3) {
                        fields.add(Introspector.decapitalize(name.substring(3)));
                    } else if (name.startsWith("is") && name.length() > 2) {
                        fields.add(Introspector.decapitalize(name.substring(2)));
                    }
                }
            }
            return fields;
        });
    }

    private static void compare(
            List<Map<String, Object>> changes, String prefix,
            Map<String, Object> before, Map<String, Object> after, Set<String> ignored) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (ignored.contains(key)) {
                continue;
            }
            Object from = before.get(key);
            Object to = after.get(key);
            if (!same(from, to)) {
                if (sensitive(key)) {
                    changes.add(change(prefix + key, from != null ? HIDDEN : null, to != null ? HIDDEN : null));
                } else {
                    changes.add(change(prefix + key, display(from), display(to)));
                }
            }
        }
    }

    private static boolean same(Object from, Object to) {
        if (from instanceof Number fromNumber && to instanceof Number toNumber) {
            return Double.compare(fromNumber.doubleValue(), toNumber.doubleValue()) == 0;
        }
        String fromText = from != null ? from.toString() : "";
        String toText = to != null ? to.toString() : "";
        return fromText.equals(toText);
    }

    private static boolean sensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.endsWith("key") || lower.equals("salt");
    }

    private static String display(Object value) {
        return value != null ? StringUtils.abbreviate(value.toString(), MAX_VALUE_LENGTH) : null;
    }

    private static Map<String, Object> change(String field, Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field", field);
        change.put("from", from);
        change.put("to", to);
        return change;
    }

    // Si no entran todos, se guardan los primeros y cuántos quedaron afuera.
    private void putChanges(Action action, List<Map<String, Object>> changes) {
        List<Map<String, Object>> kept = new ArrayList<>(changes);
        action.getAttributes().put("changes", kept);
        int omitted = 0;
        while (kept.size() > 1 && attributesLength(action) > MAX_ATTRIBUTES_LENGTH) {
            kept.remove(kept.size() - 1);
            omitted += 1;
        }
        if (omitted > 0) {
            action.set("changesOmitted", omitted);
        }
    }

    private int attributesLength(Action action) {
        try {
            return objectMapper.writeValueAsString(action.getAttributes()).length();
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }

    private void storeAction(Action action) {
        try {
            storage.addObject(action, new Request(new Columns.Exclude("id")));
        } catch (StorageException e) {
            LOGGER.warn("Failed to store action {}", action.getActionType());
        }
    }

}
