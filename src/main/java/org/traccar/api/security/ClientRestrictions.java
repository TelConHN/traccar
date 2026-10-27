/*
 * Copyright 2026 TelConHN
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
package org.traccar.api.security;

import org.traccar.model.BaseModel;
import org.traccar.model.Device;
import org.traccar.model.ExtendedModel;
import org.traccar.model.User;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

// Lo que la administración le habilita a cada cliente y que el cliente no puede cambiarse solo.
//
// Traccar deja que una cuenta que no es de solo lectura edite TODOS los atributos de su cuenta y
// de sus vehículos. Con eso un cliente podía darse los reportes que no pagó, el límite de
// velocidad, los anuncios o los módulos de Rutas/Transporte con una sola llamada a la API (la
// pantalla no lo ofrece, pero el servidor lo aceptaba). Las cuentas que crea el panel nacen de
// solo lectura y no podían; las viejas y las creadas a mano sí.
public final class ClientRestrictions {

    public static final String KEY_ALLOWED_REPORTS = "allowedReports";

    // Sin el atributo (nunca se configuró) el cliente entraba directo al recorrido: el menú lo
    // mandaba ahí. Se conserva para no quitárselo a nadie al activar el control en el servidor.
    private static final Set<String> DEFAULT_REPORTS = Set.of("replay");

    // Solo un administrador los pone o los quita. En una cuenta que no es administradora se
    // conserva lo guardado, como con los atributos protegidos de las geocercas.
    private static final Map<Class<? extends BaseModel>, Set<String>> PROTECTED = Map.of(
            User.class, Set.of(
                    KEY_ALLOWED_REPORTS,
                    "speedLimitEnabled",
                    "anunciosEnabled",
                    // Permiso de la CUENTA para bloquear el motor (lo lee admin-api en tc_users).
                    "bloqueoMotor",
                    "showDetails",
                    "billingLink",
                    "data.positionRetentionDays"),
            Device.class, Set.of(
                    "rutas",
                    "transporte",
                    "transporteVelocidad",
                    "motorBloqueado",
                    "demo",
                    "speedLimit",
                    "speedLimitSupported",
                    "speedLimitCommand"));

    private ClientRestrictions() {
    }

    // Admite varios nombres porque dos reportes usan la misma consulta (el recorrido y Posiciones
    // piden /api/positions; la gráfica y la exportación de Posiciones, /api/reports/route).
    public static boolean reportAllowed(User user, String... reports) {
        if (user.getAdministrator()) {
            return true;
        }
        Object value = user.getAttributes().get(KEY_ALLOWED_REPORTS);
        Set<String> allowed = value == null
                ? DEFAULT_REPORTS
                : Set.copyOf(Arrays.asList(value.toString().split(",")));
        return Arrays.stream(reports).anyMatch(allowed::contains);
    }

    // stored == null: es nuevo, los protegidos simplemente no se aceptan.
    public static void keepProtected(BaseModel entity, BaseModel stored) {
        Set<String> keys = PROTECTED.get(entity.getClass());
        if (keys == null || !(entity instanceof ExtendedModel after)) {
            return;
        }
        ExtendedModel before = stored instanceof ExtendedModel model ? model : null;
        for (String key : keys) {
            if (before != null && before.hasAttribute(key)) {
                after.getAttributes().put(key, before.getAttributes().get(key));
            } else {
                after.getAttributes().remove(key);
            }
        }
    }

}
