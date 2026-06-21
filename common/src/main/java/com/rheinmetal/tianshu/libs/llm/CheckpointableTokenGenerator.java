package com.rheinmetal.tianshu.libs.llm;

interface CheckpointableTokenGenerator extends InferenceTokenGenerator {
    GenerationCheckpoint checkpoint();
}
