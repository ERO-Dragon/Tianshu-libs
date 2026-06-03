package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppInstructProcessor;
import org.argeo.jjml.llm.LlamaCppSamplerChain;

/**
 * Exposes writeFormatted so the full chat history can be formatted once before
 * tokenization. This is required for Jinja chat templates and thinking options.
 */
public class FormattedPromptProcessor extends LlamaCppInstructProcessor {

    public FormattedPromptProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain) {
        super(context, samplerChain);
    }

    public void writePreFormatted(String prompt) {
        writeFormatted(prompt);
    }
}
