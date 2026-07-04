package com.rheinmetal.tianshu.libs.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.argeo.jjml.llm.LlamaCppChatTool;

import java.util.ArrayList;
import java.util.List;

final class LlmChatTools {
    private LlmChatTools() {
    }

    static List<LlamaCppChatTool> parseToolsJson(String toolsJson) {
        if (toolsJson == null || toolsJson.isBlank()) return List.of();
        JsonElement root;
        try {
            root = JsonParser.parseString(toolsJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("toolsJson must be valid JSON", e);
        }

        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("tools") && object.get("tools").isJsonArray()) {
                array = object.getAsJsonArray("tools");
            } else {
                array = new JsonArray();
                array.add(object);
            }
        } else {
            throw new IllegalArgumentException("toolsJson must be a JSON array or object");
        }

        List<LlamaCppChatTool> tools = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("toolsJson entries must be JSON objects");
            }
            tools.add(toTool(element.getAsJsonObject()));
        }
        return List.copyOf(tools);
    }

    private static LlamaCppChatTool toTool(JsonObject object) {
        String type = stringMember(object, "type");
        if (!"function".equals(type)) {
            throw new IllegalArgumentException("tool type must be 'function'");
        }
        if (!object.has("function") || !object.get("function").isJsonObject()) {
            throw new IllegalArgumentException("tool function declaration is required");
        }
        JsonObject function = object.getAsJsonObject("function");
        String name = stringMember(function, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool function name is required");
        }
        String description = stringMember(function, "description");
        JsonElement parameters = function.get("parameters");
        if (parameters == null || parameters.isJsonNull()) {
            parameters = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}");
        }
        if (!parameters.isJsonObject()) {
            throw new IllegalArgumentException("tool function parameters must be a JSON object");
        }
        return new LlamaCppChatTool(name.trim(), description == null ? "" : description, parameters.toString());
    }

    private static String stringMember(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : null;
    }
}
