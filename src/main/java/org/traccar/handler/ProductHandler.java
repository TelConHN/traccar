/*
 * Copyright 2026 Telconhn
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
package org.traccar.handler;

import jakarta.inject.Inject;
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Arma la lista de productos que lleva el vehículo y la deja en cada posición ({@code products}), así se ve
 * en el mapa y queda en el historial. Es la idea del conductor por tarjeta, con dos diferencias: el código
 * llega por OTRAS entradas del GPS (la del conductor, driverUniqueId, nunca se toca) y van muchos a la vez.
 *
 * <p>Dos clases de lector, según lo que instale el técnico:
 * <ul>
 * <li>Lector que se pasa a mano (código de barras, segundo RFID): la entrada se configura en
 * {@code processing.productAttributes}. Cada lectura de un código lo carga si no estaba y lo descarga si
 * estaba. Si el lector repite el mismo código en reportes seguidos, cuenta una sola vez: para descargar el
 * mismo producto que se acaba de cargar tiene que haber otra lectura en el medio o un reporte sin código.</li>
 * <li>Etiquetas Bluetooth pegadas al producto ({@code tagNId}, {@code beaconN…}): están mientras el GPS las
 * detecta; si pasan {@code processing.productTagTimeout} segundos sin verlas, se dan por descargadas.</li>
 * </ul>
 *
 * <p>Lo asignado a mano (vincular el producto al vehículo, como un conductor) no pasa por acá: la web lo suma.
 */
public class ProductHandler extends BasePositionHandler {

    private static final Pattern TAG = Pattern.compile("(tag|beacon)(\\d+)(Id|Instance|Uuid)");

    private final CacheManager cacheManager;

    @Inject
    public ProductHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    static String normalize(Object value) {
        if (value instanceof Double || value instanceof Float) {
            // Algunos protocolos (OsmAnd, por ejemplo) convierten a número todo lo que lo parece: un
            // código de barras 7501055300013 llegaría como 7.501055300013E12.
            BigDecimal number = BigDecimal.valueOf(((Number) value).doubleValue());
            if (number.stripTrailingZeros().scale() <= 0) {
                return number.toBigInteger().toString();
            }
        }
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", "").toUpperCase();
    }

    static Set<String> parseList(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null) {
            for (String code : value.split(",")) {
                if (!code.isEmpty()) {
                    result.add(code);
                }
            }
        }
        return result;
    }

    static Map<String, Long> parseSeen(String value) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (value != null) {
            for (String item : value.split(",")) {
                int separator = item.lastIndexOf('=');
                if (separator > 0) {
                    try {
                        result.put(item.substring(0, separator), Long.parseLong(item.substring(separator + 1)));
                    } catch (NumberFormatException ignored) {
                        // un valor roto no debe frenar el resto de la carga
                    }
                }
            }
        }
        return result;
    }

    /** Código de cada etiqueta Bluetooth de la posición: el id del tag, la instancia Eddystone o el iBeacon. */
    static Set<String> tagCodes(Position position) {
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : position.getAttributes().entrySet()) {
            Matcher matcher = TAG.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            String code = normalize(entry.getValue());
            if (matcher.group(3).equals("Uuid")) {
                // Los iBeacon de un mismo lote comparten UUID: lo que distingue a cada uno es major y minor.
                String prefix = matcher.group(1) + matcher.group(2);
                code = code + "-" + normalize(position.getAttributes().get(prefix + "Major"))
                        + "-" + normalize(position.getAttributes().get(prefix + "Minor"));
            }
            if (!code.isEmpty()) {
                result.add(code);
            }
        }
        return result;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        Position last = cacheManager.getPosition(position.getDeviceId());
        // Un reporte atrasado (memoria del GPS que llega después) no cambia la carga de ahora.
        if (last != null && position.getFixTime() != null && last.getFixTime() != null
                && position.getFixTime().before(last.getFixTime())) {
            callback.processed(false);
            return;
        }

        Set<String> products = parseList(last != null ? last.getString(Position.KEY_PRODUCTS) : null);
        Map<String, Long> seen = parseSeen(last != null ? last.getString(Position.KEY_PRODUCTS_SEEN) : null);

        String readers = AttributeUtil.lookup(cacheManager, Keys.PROCESSING_PRODUCT_ATTRIBUTES, position.getDeviceId());
        if (readers != null) {
            for (String attribute : readers.split("[ ,]")) {
                if (attribute.isEmpty() || attribute.equals(Position.KEY_DRIVER_UNIQUE_ID)) {
                    continue;
                }
                String code = normalize(position.getAttributes().get(attribute));
                if (code.isEmpty() || code.matches("0+")) {
                    continue;
                }
                if (last != null && code.equals(normalize(last.getAttributes().get(attribute)))) {
                    continue; // el lector repite la lectura anterior
                }
                if (!products.remove(code)) {
                    products.add(code);
                }
                seen.remove(code);
            }
        }

        long now = (position.getFixTime() != null ? position.getFixTime().getTime() : System.currentTimeMillis())
                / 1000;
        for (String code : tagCodes(position)) {
            products.add(code);
            seen.put(code, now);
        }
        Long timeout = AttributeUtil.lookup(cacheManager, Keys.PROCESSING_PRODUCT_TAG_TIMEOUT, position.getDeviceId());
        seen.entrySet().removeIf(entry -> {
            boolean gone = now - entry.getValue() > timeout;
            if (gone) {
                products.remove(entry.getKey());
            }
            return gone;
        });

        if (!products.isEmpty()) {
            position.set(Position.KEY_PRODUCTS, String.join(",", products));
        }
        if (!seen.isEmpty()) {
            position.set(Position.KEY_PRODUCTS_SEEN, seen.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(",")));
        }
        callback.processed(false);
    }

}
