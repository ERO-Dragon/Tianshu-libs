// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LLamaCppNativeChatFormatter {
   public LLamaCppNativeChatFormatter() {
   }

   private static native byte[] doFormatChatMessages(byte[][] var0, byte[][] var1, boolean var2, byte[] var3);

   static String formatChatMessages(List<LlamaCppChatMessage> messages, Predicate<LlamaCppChatMessage> addAssistantTokens, String chatTemplate) {
      List<LlamaCppChatMessage> msgs = (List)messages.stream().filter(Objects::nonNull).collect(Collectors.toList());
      byte[][] roles = new byte[msgs.size()][];
      byte[][] contents = new byte[msgs.size()][];
      boolean currIsUserRole = false;

      for(int i = 0; i < msgs.size(); ++i) {
         LlamaCppChatMessage message = (LlamaCppChatMessage)msgs.get(i);
         if (message != null) {
            roles[i] = message.getRole().getBytes(StandardCharsets.UTF_8);
            currIsUserRole = addAssistantTokens.test(message);
            contents[i] = message.getContent().getBytes(StandardCharsets.UTF_8);
         }
      }

      byte[] res = doFormatChatMessages(roles, contents, currIsUserRole, chatTemplate.getBytes(StandardCharsets.UTF_8));
      return new String(res, StandardCharsets.UTF_8);
   }
}
