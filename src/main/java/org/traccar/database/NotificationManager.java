/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.forward.EventData;
import org.traccar.forward.EventForwarder;
import org.traccar.geocoder.Geocoder;
import org.traccar.helper.DateUtil;
import org.traccar.model.Calendar;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Maintenance;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.notification.MessageException;
import org.traccar.notification.NotificatorManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

@Singleton
public class NotificationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationManager.class);

    // A quién se le avisó y por qué canal queda en el mismo evento, para que la Auditoría muestre si el aviso
    // salió. "sent" es que el canal lo aceptó sin error, no que la persona lo leyó.
    private static final String DELIVERY_SENT = "sent";
    private static final String DELIVERY_FAILED = "failed";
    private static final String DELIVERY_BLOCKED = "blocked";
    private static final String DELIVERY_NO_PHONE = "noPhone";
    private static final String DELIVERY_NO_APP = "noApp";
    // tc_events.attributes es VARCHAR(4000) y el evento ya trae lo suyo.
    private static final int MAX_DELIVERIES = 20;
    private static final int MAX_ERROR_LENGTH = 80;

    private final Storage storage;
    private final CacheManager cacheManager;
    private final EventForwarder eventForwarder;
    private final NotificatorManager notificatorManager;
    private final Geocoder geocoder;

    private final boolean geocodeOnRequest;
    private final long timeThreshold;
    private final Set<Long> blockedUsers = new HashSet<>();

    @Inject
    public NotificationManager(
            Config config, Storage storage, CacheManager cacheManager, @Nullable EventForwarder eventForwarder,
            NotificatorManager notificatorManager, @Nullable Geocoder geocoder) {
        this.storage = storage;
        this.cacheManager = cacheManager;
        this.eventForwarder = eventForwarder;
        this.notificatorManager = notificatorManager;
        this.geocoder = geocoder;
        geocodeOnRequest = config.getBoolean(Keys.GEOCODER_ON_REQUEST);
        timeThreshold = config.getLong(Keys.NOTIFICATOR_TIME_THRESHOLD);
        String blockedUsersString = config.getString(Keys.NOTIFICATION_BLOCK_USERS);
        if (blockedUsersString != null) {
            for (String userIdString : blockedUsersString.split(",")) {
                blockedUsers.add(Long.parseLong(userIdString));
            }
        }
    }

    private void updateEvent(Event event, Position position) {
        try {
            event.setId(storage.addObject(event, new Request(new Columns.Exclude("id"))));
        } catch (StorageException error) {
            LOGGER.warn("Event save error", error);
        }

        forwardEvent(event, position);

        if (System.currentTimeMillis() - event.getEventTime().getTime() > timeThreshold) {
            LOGGER.info("Skipping notifications for old event");
            return;
        }

        var notifications = cacheManager.getDeviceNotifications(event.getDeviceId()).stream()
                .filter(notification -> notification.getType().equals(event.getType()))
                .filter(notification -> {
                    if (event.getType().equals(Event.TYPE_ALARM)) {
                        String alarmsAttribute = notification.getString("alarms");
                        if (alarmsAttribute != null) {
                            return Arrays.asList(alarmsAttribute.split(","))
                                    .contains(event.getString(Position.KEY_ALARM));
                        }
                        return false;
                    }
                    return true;
                })
                .filter(notification -> {
                    String geofenceIdsString = notification.getString("geofenceIds");
                    if (geofenceIdsString != null) {
                        return Arrays.stream(geofenceIdsString.split(",")).map(Long::parseLong)
                                .anyMatch(geofenceId -> geofenceId == event.getGeofenceId());
                    }
                    return true;
                })
                .filter(notification -> {
                    long calendarId = notification.getCalendarId();
                    Calendar calendar = calendarId != 0 ? cacheManager.getObject(Calendar.class, calendarId) : null;
                    return calendar == null || calendar.checkMoment(event.getEventTime());
                })
                .toList();

        Device device = cacheManager.getObject(Device.class, event.getDeviceId());
        LOGGER.info(
                "Event id: {}, time: {}, type: {}, notifications: {}",
                device.getUniqueId(),
                DateUtil.formatDate(event.getEventTime(), false),
                event.getType(),
                notifications.size());

        if (!notifications.isEmpty()) {
            if (position != null && position.getAddress() == null && geocodeOnRequest && geocoder != null) {
                position.setAddress(geocoder.getAddress(position.getLatitude(), position.getLongitude(), null));
            }

            boolean skipAdministrators = geofenceSkipsAdministrators(event);
            Map<String, Map<String, Object>> deliveries = new LinkedHashMap<>();
            notifications.forEach(notification -> {
                cacheManager.getNotificationUsers(notification.getId(), event.getDeviceId()).forEach(user -> {
                    if (blockedUsers.contains(user.getId())) {
                        LOGGER.info("User {} notification blocked", user.getId());
                        addDelivery(deliveries, user, null, DELIVERY_BLOCKED, null);
                        return;
                    }
                    if (skipAdministrators && user.getAdministrator()) {
                        return;
                    }
                    for (String notificator : notification.getNotificatorsTypes()) {
                        String skipped = skippedReason(notificator, user);
                        if (skipped != null) {
                            addDelivery(deliveries, user, notificator, skipped, null);
                            continue;
                        }
                        try {
                            notificatorManager.getNotificator(notificator).send(notification, user, event, position);
                            addDelivery(deliveries, user, notificator, DELIVERY_SENT, null);
                        } catch (MessageException exception) {
                            LOGGER.warn("Notification failed", exception);
                            addDelivery(deliveries, user, notificator, DELIVERY_FAILED, exception.getMessage());
                        } catch (RuntimeException exception) {
                            // Canal no habilitado en el servidor (o que revienta): antes cortaba el envío a
                            // todas las demás cuentas y canales de este evento.
                            LOGGER.warn("Notification failed", exception);
                            addDelivery(deliveries, user, notificator, DELIVERY_FAILED, exception.getMessage());
                        }
                    }
                });
            });
            storeDeliveries(event, deliveries);
        }
    }

    // Casos en que el canal no manda nada y no da error: sin esto se verían como "enviado".
    private static String skippedReason(String notificator, User user) {
        return switch (notificator) {
            case "sms", "whatsapp" -> user.getPhone() == null || user.getPhone().isBlank() ? DELIVERY_NO_PHONE : null;
            case "firebase" -> user.hasAttribute("notificationTokens") ? null : DELIVERY_NO_APP;
            default -> null;
        };
    }

    private static void addDelivery(
            Map<String, Map<String, Object>> deliveries, User user, String channel, String status, String error) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("userId", user.getId());
        if (channel != null) {
            delivery.put("channel", channel);
        }
        delivery.put("status", status);
        if (error != null) {
            delivery.put("error", error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error);
        }
        // La misma cuenta puede estar en dos notificaciones del mismo tipo: se anota una vez por canal.
        deliveries.putIfAbsent(user.getId() + ":" + channel, delivery);
    }

    private void storeDeliveries(Event event, Map<String, Map<String, Object>> deliveries) {
        if (deliveries.isEmpty() || event.getId() == 0) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(deliveries.values());
        if (list.size() > MAX_DELIVERIES) {
            event.set("notifiedOmitted", list.size() - MAX_DELIVERIES);
            list = new ArrayList<>(list.subList(0, MAX_DELIVERIES));
        }
        event.getAttributes().put("notified", list);
        try {
            // Con la hora el UPDATE va directo a la partición del evento (tc_events es hypertable).
            storage.updateObject(event, new Request(
                    new Columns.Include("attributes"),
                    new Condition.And(
                            new Condition.Equals("id", event.getId()),
                            new Condition.Equals("eventTime", event.getEventTime()))));
        } catch (StorageException error) {
            LOGGER.warn("Event delivery save error", error);
        }
    }

    // Solo entrada/salida: un exceso de velocidad también puede traer geofenceId (límite de la
    // geocerca) y ese aviso a administración no se toca.
    private boolean geofenceSkipsAdministrators(Event event) {
        if (event.getGeofenceId() == 0
                || !(event.getType().equals(Event.TYPE_GEOFENCE_ENTER)
                        || event.getType().equals(Event.TYPE_GEOFENCE_EXIT))) {
            return false;
        }
        Geofence geofence = cacheManager.getObject(Geofence.class, event.getGeofenceId());
        return geofence != null
                && geofence.hasAttribute(Geofence.KEY_NOTIFY_ADMINISTRATORS)
                && !geofence.getBoolean(Geofence.KEY_NOTIFY_ADMINISTRATORS);
    }

    private void forwardEvent(Event event, Position position) {
        if (eventForwarder != null) {
            EventData eventData = new EventData();
            eventData.setEvent(event);
            eventData.setPosition(position);
            eventData.setDevice(cacheManager.getObject(Device.class, event.getDeviceId()));
            if (event.getGeofenceId() != 0) {
                eventData.setGeofence(cacheManager.getObject(Geofence.class, event.getGeofenceId()));
            }
            if (event.getMaintenanceId() != 0) {
                eventData.setMaintenance(cacheManager.getObject(Maintenance.class, event.getMaintenanceId()));
            }
            eventForwarder.forward(eventData, (success, throwable) -> {
                if (!success) {
                    LOGGER.warn("Event forwarding failed", throwable);
                }
            });
        }
    }

    public void updateEvents(Map<Event, Position> events) {
        for (Entry<Event, Position> entry : events.entrySet()) {
            Event event = entry.getKey();
            Position position = entry.getValue();
            var key = new Object();
            try {
                cacheManager.addDevice(event.getDeviceId(), key);
                updateEvent(event, position);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                cacheManager.removeDevice(event.getDeviceId(), key);
            }
        }
    }
}
