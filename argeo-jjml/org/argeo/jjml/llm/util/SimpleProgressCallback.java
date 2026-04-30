// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.util;

import java.io.PrintStream;
import java.util.function.DoubleConsumer;

public class SimpleProgressCallback implements DoubleConsumer {
   private int lastPerctPrinted;
   private final PrintStream out;

   public SimpleProgressCallback(PrintStream out) {
      this.lastPerctPrinted = -1;
      this.out = out;
   }

   public SimpleProgressCallback() {
      this(System.err);
   }

   protected void printProgressBar(char[] progressBar) {
      if (this.out != null) {
         PrintStream var10000 = this.out;
         String var10001 = new String(progressBar);
         var10000.print("\r" + var10001);
      }

   }

   public void accept(double progress) {
      char[] progressBar = new char[10];
      int perct = (int)(progress * (double)100.0F);
      if (perct > this.lastPerctPrinted + 10 || this.lastPerctPrinted == -1 || progress == (double)1.0F) {
         for(int i = 0; i < perct / 10; ++i) {
            progressBar[i] = '#';
         }

         for(int i = perct / 10; i < 10; ++i) {
            progressBar[i] = '-';
         }

         this.printProgressBar(progressBar);
         this.lastPerctPrinted = perct;
         if (progress == (double)1.0F && this.out != null) {
            this.out.print("\n");
         }
      }

   }
}
