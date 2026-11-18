package com.echomind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "echomind")
public class EchoMindProperties {

    private final Llm llm = new Llm();
    private final Memory memory = new Memory();
    private final Rag rag = new Rag();
    private final Storage storage = new Storage();
    private final Monitor monitor = new Monitor();
    private final Eval eval = new Eval();
    private final Skills skills = new Skills();
    private final Security security = new Security();

    public Llm getLlm() {
        return llm;
    }

    public Memory getMemory() {
        return memory;
    }

    public Rag getRag() {
        return rag;
    }

    public Storage getStorage() {
        return storage;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public Eval getEval() {
        return eval;
    }

    public Skills getSkills() {
        return skills;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Llm {
        private boolean fallbackEnabled = true;

        public boolean isFallbackEnabled() {
            return fallbackEnabled;
        }

        public void setFallbackEnabled(boolean fallbackEnabled) {
            this.fallbackEnabled = fallbackEnabled;
        }
    }

    public static class Memory {
        private long ttlSeconds = 86400;
        private int workingMax = 20;
        private int compressAt = 15;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getWorkingMax() {
            return workingMax;
        }

        public void setWorkingMax(int workingMax) {
            this.workingMax = workingMax;
        }

        public int getCompressAt() {
            return compressAt;
        }

        public void setCompressAt(int compressAt) {
            this.compressAt = compressAt;
        }
    }

    public static class Rag {
        private int topK = 4;
        private double bm25Weight = 0.45;
        private double vectorWeight = 0.55;
        private String embeddingModel = "bge-small-zh-v1.5-onnx-fp32";
        private String embeddingModelPath =
                "models/bge-small-zh-v1.5/model.onnx";
        private String tokenizerPath =
                "models/bge-small-zh-v1.5/tokenizer.json";
        private int embeddingMaxLength = 512;
        private int onnxIntraOpThreads =
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getBm25Weight() {
            return bm25Weight;
        }

        public void setBm25Weight(double bm25Weight) {
            this.bm25Weight = bm25Weight;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getEmbeddingModelPath() {
            return embeddingModelPath;
        }

        public void setEmbeddingModelPath(String embeddingModelPath) {
            this.embeddingModelPath = embeddingModelPath;
        }

        public String getTokenizerPath() {
            return tokenizerPath;
        }

        public void setTokenizerPath(String tokenizerPath) {
            this.tokenizerPath = tokenizerPath;
        }

        public int getEmbeddingMaxLength() {
            return embeddingMaxLength;
        }

        public void setEmbeddingMaxLength(int embeddingMaxLength) {
            this.embeddingMaxLength = embeddingMaxLength;
        }

        public int getOnnxIntraOpThreads() {
            return onnxIntraOpThreads;
        }

        public void setOnnxIntraOpThreads(int onnxIntraOpThreads) {
            this.onnxIntraOpThreads = onnxIntraOpThreads;
        }
    }

    public static class Storage {
        private String dataDir = "data/java";
        private String knowledgePath = "data/java/knowledge-store.json";
        private String memoryPath = "data/java/memory-store.json";

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        public String getKnowledgePath() {
            return knowledgePath;
        }

        public void setKnowledgePath(String knowledgePath) {
            this.knowledgePath = knowledgePath;
        }

        public String getMemoryPath() {
            return memoryPath;
        }

        public void setMemoryPath(String memoryPath) {
            this.memoryPath = memoryPath;
        }
    }

    public static class Monitor {
        private double successRateThreshold = 0.90;
        private double latencyMsThreshold = 3000;
        private String webhookUrl = "";

        public double getSuccessRateThreshold() {
            return successRateThreshold;
        }

        public void setSuccessRateThreshold(double successRateThreshold) {
            this.successRateThreshold = successRateThreshold;
        }

        public double getLatencyMsThreshold() {
            return latencyMsThreshold;
        }

        public void setLatencyMsThreshold(double latencyMsThreshold) {
            this.latencyMsThreshold = latencyMsThreshold;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }

    public static class Eval {
        private String baselinePath = "data/eval/baseline.json";
        private String jobsPath = "data/eval/jobs.json";
        private GoldenGate goldenGate = new GoldenGate();

        public String getBaselinePath() {
            return baselinePath;
        }

        public void setBaselinePath(String baselinePath) {
            this.baselinePath = baselinePath;
        }

        public String getJobsPath() {
            return jobsPath;
        }

        public void setJobsPath(String jobsPath) {
            this.jobsPath = jobsPath;
        }

        public GoldenGate getGoldenGate() {
            return goldenGate;
        }

        public void setGoldenGate(GoldenGate goldenGate) {
            this.goldenGate = goldenGate;
        }
    }

    public static class GoldenGate {
        private double maxMetricDrop = 0.02;
        private int maxAclViolations = 0;
        private double maxDegradedRate = 0.0;
        private double minHandoffRecall = 0.95;
        private double maxFalseEscalationRate = 0.10;
        private double maxMissedHandoffRate = 0.10;
        private long p95LatencyAdvisoryMs = 2000;

        public double getMaxMetricDrop() {
            return maxMetricDrop;
        }

        public void setMaxMetricDrop(double maxMetricDrop) {
            this.maxMetricDrop = maxMetricDrop;
        }

        public int getMaxAclViolations() {
            return maxAclViolations;
        }

        public void setMaxAclViolations(int maxAclViolations) {
            this.maxAclViolations = maxAclViolations;
        }

        public double getMaxDegradedRate() {
            return maxDegradedRate;
        }

        public void setMaxDegradedRate(double maxDegradedRate) {
            this.maxDegradedRate = maxDegradedRate;
        }

        public double getMinHandoffRecall() {
            return minHandoffRecall;
        }

        public void setMinHandoffRecall(double minHandoffRecall) {
            this.minHandoffRecall = minHandoffRecall;
        }

        public double getMaxFalseEscalationRate() {
            return maxFalseEscalationRate;
        }

        public void setMaxFalseEscalationRate(double maxFalseEscalationRate) {
            this.maxFalseEscalationRate = maxFalseEscalationRate;
        }

        public double getMaxMissedHandoffRate() {
            return maxMissedHandoffRate;
        }

        public void setMaxMissedHandoffRate(double maxMissedHandoffRate) {
            this.maxMissedHandoffRate = maxMissedHandoffRate;
        }

        public long getP95LatencyAdvisoryMs() {
            return p95LatencyAdvisoryMs;
        }

        public void setP95LatencyAdvisoryMs(long p95LatencyAdvisoryMs) {
            this.p95LatencyAdvisoryMs = p95LatencyAdvisoryMs;
        }
    }

    public static class Skills {
        private String rootDir = "skills";
        private int maxPromptChars = 5000;

        public String getRootDir() {
            return rootDir;
        }

        public void setRootDir(String rootDir) {
            this.rootDir = rootDir;
        }

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }
    }

    public static class Security {
        private final Jwt jwt = new Jwt();

        public Jwt getJwt() {
            return jwt;
        }
    }

    public static class Jwt {
        private String issuer = "echomind-local";
        private String audience = "echomind-api";

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }
    }
}
