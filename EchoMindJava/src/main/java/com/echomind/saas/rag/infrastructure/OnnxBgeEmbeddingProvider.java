package com.echomind.saas.rag.infrastructure;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.echomind.config.EchoMindProperties;
import com.echomind.saas.rag.application.EmbeddingProvider;
import com.echomind.saas.rag.application.EmbeddingUnavailableException;
import jakarta.annotation.PreDestroy;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class OnnxBgeEmbeddingProvider
        implements EmbeddingProvider, AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OnnxBgeEmbeddingProvider.class);
    private static final String QUERY_INSTRUCTION =
            "为这个句子生成表示以用于检索相关文章：";

    private final Path modelPath;
    private final Path tokenizerPath;
    private final int maxLength;
    private final int intraOpThreads;
    private final String modelName;
    private final Object initializationLock = new Object();
    private final Object inferenceLock = new Object();

    private volatile boolean initialized;
    private volatile String initializationError = "";
    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public OnnxBgeEmbeddingProvider(EchoMindProperties properties) {
        EchoMindProperties.Rag rag = properties.getRag();
        this.modelPath = resolve(rag.getEmbeddingModelPath());
        this.tokenizerPath = resolve(rag.getTokenizerPath());
        this.maxLength = Math.max(32, Math.min(512, rag.getEmbeddingMaxLength()));
        this.intraOpThreads = Math.max(1, rag.getOnnxIntraOpThreads());
        this.modelName = rag.getEmbeddingModel();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public boolean available() {
        ensureInitialized();
        return session != null;
    }

    @Override
    public double[] embed(String text) {
        return infer(String.valueOf(text == null ? "" : text));
    }

    @Override
    public double[] embedQuery(String text) {
        return infer(QUERY_INSTRUCTION + String.valueOf(text == null ? "" : text));
    }

    @PreDestroy
    public void close() {
        synchronized (initializationLock) {
            closeQuietly(tokenizer);
            closeQuietly(session);
            tokenizer = null;
            session = null;
            initialized = false;
        }
    }

    private double[] infer(String text) {
        ensureInitialized();
        if (session == null || tokenizer == null) {
            throw new EmbeddingUnavailableException(
                    "BGE ONNX embedding is unavailable: " + initializationError);
        }

        synchronized (inferenceLock) {
            Encoding encoding = tokenizer.encode(text);
            long[] ids = truncate(encoding.getIds());
            long[] attention = truncate(encoding.getAttentionMask());
            long[] types = truncate(encoding.getTypeIds());
            try (OnnxTensor inputIds = OnnxTensor.createTensor(
                            environment, new long[][]{ids});
                    OnnxTensor attentionMask = OnnxTensor.createTensor(
                            environment, new long[][]{attention});
                    OnnxTensor tokenTypes = OnnxTensor.createTensor(
                            environment, new long[][]{types});
                    OrtSession.Result result = session.run(Map.of(
                            "input_ids", inputIds,
                            "attention_mask", attentionMask,
                            "token_type_ids", tokenTypes))) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                long[] shape = output.getInfo().getShape();
                int hiddenSize = Math.toIntExact(shape[shape.length - 1]);
                FloatBuffer values = output.getFloatBuffer();
                double[] embedding = new double[hiddenSize];
                double norm = 0.0;
                for (int index = 0; index < hiddenSize; index++) {
                    double value = values.get(index);
                    embedding[index] = value;
                    norm += value * value;
                }
                if (norm == 0.0) {
                    throw new EmbeddingUnavailableException(
                            "BGE ONNX returned an empty CLS embedding");
                }
                double divisor = Math.sqrt(norm);
                for (int index = 0; index < embedding.length; index++) {
                    embedding[index] /= divisor;
                }
                return embedding;
            } catch (OrtException exception) {
                throw new EmbeddingUnavailableException(
                        "BGE ONNX inference failed", exception);
            }
        }
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (initializationLock) {
            if (initialized) {
                return;
            }
            Thread thread = Thread.currentThread();
            ClassLoader previousLoader = thread.getContextClassLoader();
            try {
                if (!Files.isRegularFile(modelPath)) {
                    throw new IllegalStateException(
                            "model file not found: " + modelPath);
                }
                if (!Files.isRegularFile(tokenizerPath)) {
                    throw new IllegalStateException(
                            "tokenizer file not found: " + tokenizerPath);
                }
                environment = OrtEnvironment.getEnvironment();
                try (OrtSession.SessionOptions options =
                        new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(intraOpThreads);
                    options.setInterOpNumThreads(1);
                    options.setOptimizationLevel(
                            OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    session = environment.createSession(
                            modelPath.toString(), options);
                }
                // DJL searches for native metadata using the TCCL, including on first use from a common-pool thread.
                thread.setContextClassLoader(HuggingFaceTokenizer.class.getClassLoader());
                tokenizer = HuggingFaceTokenizer.builder()
                        .optTokenizerPath(tokenizerPath)
                        .optAddSpecialTokens(true)
                        .optTruncation(true)
                        .optMaxLength(maxLength)
                        .build();
                initializationError = "";
                LOGGER.info(
                        "Loaded BGE ONNX embedding model={} path={} maxLength={}",
                        modelName,
                        modelPath,
                        maxLength);
            } catch (Exception | LinkageError exception) {
                initializationError = exception.getClass().getSimpleName()
                        + ": " + String.valueOf(exception.getMessage());
                closeQuietly(tokenizer);
                closeQuietly(session);
                tokenizer = null;
                session = null;
                LOGGER.warn(
                        "BGE ONNX embedding unavailable: {}",
                        initializationError);
            } finally {
                thread.setContextClassLoader(previousLoader);
                initialized = true;
            }
        }
    }

    private long[] truncate(long[] values) {
        if (values != null && values.length <= maxLength) {
            return values;
        }
        if (values == null || values.length == 0) {
            return new long[]{0L};
        }
        long[] truncated = new long[maxLength];
        System.arraycopy(values, 0, truncated, 0, maxLength - 1);
        truncated[maxLength - 1] = values[values.length - 1];
        return truncated;
    }

    private static Path resolve(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
