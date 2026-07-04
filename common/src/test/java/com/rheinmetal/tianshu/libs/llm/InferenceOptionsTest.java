package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppChatTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InferenceOptionsTest {
    @Test
    void normalizesToolsJson() {
        String tool = """
                {
                  "type": "function",
                  "function": {
                    "name": "load_skill",
                    "parameters": { "type": "object", "properties": {} }
                  }
                }
                """.trim();
        InferenceOptions options = InferenceOptions.builder()
                .toolsJson(" " + tool + " ")
                .build();

        assertEquals(tool, options.getToolsJson());
        assertNull(InferenceOptions.builder().toolsJson(" ").build().getToolsJson());
    }

    @Test
    void parsesMultipleOpenAiStyleTools() {
        List<LlamaCppChatTool> tools = LlmChatTools.parseToolsJson("""
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "load_skill",
                      "description": "Load a skill",
                      "parameters": {
                        "type": "object",
                        "properties": { "skill_id": { "type": "string" } },
                        "required": ["skill_id"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "query_state",
                      "description": "Query state",
                      "parameters": { "type": "object", "properties": {} }
                    }
                  }
                ]
                """);

        assertEquals(2, tools.size());
        assertEquals("load_skill", tools.get(0).name());
        assertEquals("query_state", tools.get(1).name());
    }

    @Test
    void parsesSingleOpenAiStyleTool() {
        List<LlamaCppChatTool> tools = LlmChatTools.parseToolsJson("""
                {
                  "type": "function",
                  "function": {
                    "name": "load_skill",
                    "description": "Load a skill",
                    "parameters": { "type": "object", "properties": {} }
                  }
                }
                """);

        assertEquals(1, tools.size());
        assertEquals("load_skill", tools.get(0).name());
        assertEquals("{\"type\":\"object\",\"properties\":{}}", tools.get(0).parametersJson());
    }

    @Test
    void parsesWrappedOpenAiStyleTools() {
        List<LlamaCppChatTool> tools = LlmChatTools.parseToolsJson("""
                {
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "load_skill",
                        "parameters": { "type": "object", "properties": {} }
                      }
                    }
                  ]
                }
                """);

        assertEquals(1, tools.size());
        assertEquals("load_skill", tools.get(0).name());
    }

    @Test
    void acceptsEmptyToolArray() {
        assertEquals(List.of(), LlmChatTools.parseToolsJson("[]"));
        assertEquals(List.of(), LlmChatTools.parseToolsJson("{\"tools\":[]}"));
    }

    @Test
    void rejectsSimpleToolDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> LlmChatTools.parseToolsJson("""
                {
                  "name": "load_skill",
                  "description": "Load a skill",
                  "parameters": { "type": "object", "properties": {} }
                }
                """));
    }

    @Test
    void rejectsMalformedNativeToolDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> LlmChatTools.parseToolsJson("[{}]"));
    }
}
