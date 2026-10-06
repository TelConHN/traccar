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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Action;
import org.traccar.model.BaseModel;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

public final class LogAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogAction.class);

    private final Storage storage;

    @Inject
    public LogAction(Storage storage) {
        this.storage = storage;
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

    public void create(HttpServletRequest request, long userId, BaseModel object) {
        logObjectAction(request, ACTION_CREATE, userId, object.getClass(), object.getId());
    }

    public void edit(HttpServletRequest request, long userId, BaseModel object) {
        logObjectAction(request, ACTION_EDIT, userId, object.getClass(), object.getId());
    }

    public void remove(HttpServletRequest request, long userId, Class<?> clazz, long objectId) {
        logObjectAction(request, ACTION_REMOVE, userId, clazz, objectId);
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
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(ACTION_CREATE);
        action.setObjectType("token");
        action.setObjectId(tokenId);
        storeAction(action);
    }

    public void failedLogin(HttpServletRequest request) {
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setActionType(ACTION_DENIED);
        storeAction(action);

        LOGGER.info(String.format(
                "login failed from: %s",
                StringUtils.isEmpty(action.getAddress()) ? "unknown" : action.getAddress()));
    }

    public void resetAccumulators(HttpServletRequest request, long userId, long deviceId) {
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(ACTION_ACCUMULATORS);
        action.setObjectType(Introspector.decapitalize(Device.class.getSimpleName()));
        action.setObjectId(deviceId);
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
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(ACTION_COMMAND);
        action.setObjectType(Introspector.decapitalize(Device.class.getSimpleName()));
        action.setObjectId(command.getDeviceId());
        if (groupId > 0) {
            action.set("groupId", groupId);
        }
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
        storeAction(action);
    }

    public void report(
            HttpServletRequest request, long userId, boolean scheduled, String report,
            Date from, Date to, List<Long> deviceIds, List<Long> groupIds) {
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(ACTION_REPORT);
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
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setObjectType(Introspector.decapitalize(clazz.getSimpleName()));
        action.setObjectId(objectId);
        storeAction(action);
    }

    private void logLinkAction(
            HttpServletRequest request, String actionType,
            long userId, Class<?> owner, long ownerId, Class<?> property, long propertyId) {
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(actionType);
        action.setObjectType(Introspector.decapitalize(property.getSimpleName()));
        action.setObjectId(propertyId);
        action.set("ownerType", Introspector.decapitalize(owner.getSimpleName()));
        action.set("ownerId", ownerId);
        storeAction(action);
    }

    private void logLoginAction(
            HttpServletRequest request, String actionType, long userId) {
        Action action = new Action();
        action.setAddress(WebHelper.retrieveRemoteAddress(request));
        action.setUserId(userId);
        action.setActionType(actionType);
        storeAction(action);
    }

    private void storeAction(Action action) {
        try {
            storage.addObject(action, new Request(new Columns.Exclude("id")));
        } catch (StorageException e) {
            LOGGER.warn("Failed to store action {}", action.getActionType());
        }
    }

}
