package org.traccar.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import org.traccar.handler.events.VoltageEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Las alarmas propias de la plataforma llegaban al teléfono como «Mack Volqueta HAG0217: $alarmName» (2 nov):
// sus nombres estaban en las traducciones de la web, pero no en las del servidor, que son las del mensaje.
// Al cliente le llega el nombre corto (sin «medida por la plataforma») y el voltaje medido.
public class AlarmTemplateTest {

    private static String digest(String language, String alarm, Double power) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("resource.loader.file.path", "templates/");
        VelocityEngine engine = new VelocityEngine();
        engine.init(properties);

        Map<String, String> translations = new ObjectMapper().readValue(
                new File("templates/translations/" + language + ".json"), new TypeReference<>() { });
        Device device = new Device();
        device.setName("Ford Explorer JDG5705");
        Event event = new Event(Event.TYPE_ALARM, 1);
        event.set(Position.KEY_ALARM, alarm);
        if (power != null) {
            event.set(Position.KEY_POWER, power);
        }

        VelocityContext context = new VelocityContext();
        context.put("translations", translations);
        context.put("device", device);
        context.put("event", event);
        Template template = engine.getTemplate(
                "notifications/" + language + "/alarm.vm", StandardCharsets.UTF_8.name());
        template.merge(context, new StringWriter());
        return context.get("digest").toString();
    }

    @Test
    public void testPlatformAlarmsHaveNames() throws Exception {
        assertEquals("🚨 Ford Explorer JDG5705: Batería desconectada (0.0 V)",
                digest("es", VoltageEventHandler.ALARM_POWER_DISCONNECTED, 0.0));
        assertEquals("🚨 Ford Explorer JDG5705: Batería reconectada (11.97 V)",
                digest("es", VoltageEventHandler.ALARM_POWER_RECONNECTED, 11.97));
        assertEquals("🚨 Ford Explorer JDG5705: Voltaje bajo (11.2 V)",
                digest("es", VoltageEventHandler.ALARM_LOW_VOLTAGE, 11.2));
        // Las alarmas del equipo no llevan voltaje: lo que mande en "power" no es necesariamente la batería.
        assertEquals("🚨 Ford Explorer JDG5705: Energía desconectada", digest("es", Position.ALARM_POWER_CUT, 12.4));
    }

    @Test
    public void testUnknownAlarmShowsItsKey() throws Exception {
        assertEquals("🚨 Ford Explorer JDG5705: algoNuevo", digest("es", "algoNuevo", null));
    }

}
