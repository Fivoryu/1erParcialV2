# Only opt-in trial release builds register this bridge reflectively.
-keep class com.diagrammer.diagrammer_mobile.LiteRtTrialBridge {
    public <init>(android.content.Context, io.flutter.plugin.common.BinaryMessenger);
    public void close();
}

# JNI resolves these configuration getters by literal class/member names.
-keep class com.google.ai.edge.litertlm.SamplerConfig {
    public int getTopK();
    public double getTopP();
    public double getTemperature();
    public int getSeed();
}
-keep class com.google.ai.edge.litertlm.ThinkingConfig {
    public boolean getEnableThinking();
    public int getThinkingTokenBudget();
}

# The optional production agent uses a distinct channel and the same pinned SDK.
-keep class com.diagrammer.diagrammer_mobile.LiteRtAgentBridge {
    public <init>(android.content.Context, io.flutter.plugin.common.BinaryMessenger);
    public void close();
}
