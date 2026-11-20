/*
 * Copyright 2016 - 2025 Anton Tananaev (anton@traccar.org)
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
package org.traccar.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.traccar.geofence.GeofenceCircle;
import org.traccar.geofence.GeofenceGeometry;
import org.traccar.geofence.GeofencePolygon;
import org.traccar.geofence.GeofencePolyline;
import org.traccar.storage.QueryIgnore;
import org.traccar.storage.StorageName;

import java.text.ParseException;
import java.util.List;

@StorageName("tc_geofences")
public class Geofence extends ExtendedModel implements Schedulable {

    // false = los avisos de esta geocerca no llegan a usuarios administradores (solo al cliente).
    // Sin el atributo (geocercas anteriores a esta regla) se avisa a todos, como siempre.
    public static final String KEY_NOTIFY_ADMINISTRATORS = "notificarAdministracion";
    // Id del usuario que la creó; lo pone el servidor, no el cliente.
    public static final String KEY_CREATED_BY = "creadoPor";
    // Ids de usuario (separados por coma): cada carro nuevo de esos usuarios recibe esta geocerca.
    public static final String KEY_AUTO_USERS = "autoAgregarUsuarios";
    // true = vigila todos los carros, también los que se agreguen después, sin asignarlos uno por uno.
    public static final String KEY_ALL_DEVICES = "todosLosCarros";
    // Hora (ms) en que se encendió lo anterior; la pone el servidor.
    public static final String KEY_ALL_DEVICES_SINCE = "todosLosCarrosDesde";
    // Atributo de una NOTIFICACIÓN: id de la geocerca que la creó desde su pantalla («Avisos de esta
    // geocerca»). Al borrar la geocerca se borran con ella; las creadas a mano no lo llevan.
    public static final String KEY_NOTIFICATION_OWNER = "avisoDeGeocerca";
    // Solo un administrador puede poner estos. Vigilar todos los carros, en manos de un cliente, le
    // contaría por dónde andan los carros de los demás.
    public static final List<String> ADMIN_ONLY_KEYS = List.of(
            KEY_NOTIFY_ADMINISTRATORS, KEY_AUTO_USERS, KEY_ALL_DEVICES);

    // Sin "get" para que Jackson no lo publique como campo del JSON.
    public boolean watchesAllDevices() {
        return getBoolean(KEY_ALL_DEVICES);
    }

    private long calendarId;

    @Override
    public long getCalendarId() {
        return calendarId;
    }

    @Override
    public void setCalendarId(long calendarId) {
        this.calendarId = calendarId;
    }

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    private String area;

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
        geometry = null;
    }

    private GeofenceGeometry geometry;

    @QueryIgnore
    @JsonIgnore
    public GeofenceGeometry getGeometry() {
        if (geometry == null) {
            try {
                if (area.startsWith("CIRCLE")) {
                    geometry = new GeofenceCircle(area);
                } else if (area.startsWith("POLYGON")) {
                    geometry = new GeofencePolygon(area);
                } else if (area.startsWith("LINESTRING")) {
                    geometry = new GeofencePolyline(area, getDouble("polylineDistance", 25.0));
                } else {
                    throw new IllegalArgumentException("Unknown geometry type");
                }
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }
        return geometry;
    }

    @QueryIgnore
    @JsonIgnore
    public void setGeometry(GeofenceGeometry geometry) {
        setArea(geometry.toWkt());
    }

    public boolean containsPosition(Position position) {
        double floor = getDouble("floor");
        if (floor != 0 && position.getAltitude() < floor) {
            return false;
        }

        double ceiling = getDouble("ceiling");
        if (ceiling != 0 && position.getAltitude() > ceiling) {
            return false;
        }

        return getGeometry().containsPoint(position.getLatitude(), position.getLongitude());
    }

    public boolean containsSegment(Position from, Position to) {
        double floor = getDouble("floor");
        if (floor != 0 && from.getAltitude() < floor && to.getAltitude() < floor) {
            return false;
        }

        double ceiling = getDouble("ceiling");
        if (ceiling != 0 && from.getAltitude() > ceiling && to.getAltitude() > ceiling) {
            return false;
        }

        return getGeometry().intersectsSegment(
                from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
    }

}
