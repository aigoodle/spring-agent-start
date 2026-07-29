package io.github.aigoodle.tool.builtin;

import io.github.aigoodle.tool.AbstractAgentTool;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Returns the current date-time, optionally in a given IANA timezone
 * (argument {@code zone}, e.g. {@code Asia/Shanghai}).
 */
public class CurrentTimeTool extends AbstractAgentTool {

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "Get the current date and time. Optional argument: 'zone' (IANA id, e.g. 'Asia/Shanghai').";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"zone\":{\"type\":\"string\"}}}";
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String zone = str(args, "zone", "UTC");
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zone));
            return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        } catch (Exception e) {
            return "error: invalid zone '" + zone + "'";
        }
    }
}
