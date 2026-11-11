package com.rheinmetal.tianshu.foundry.llm;

interface CheckpointableTokenGenerator extends InferenceTokenGenerator {
    GenerationCheckpoint checkpoint();
}
