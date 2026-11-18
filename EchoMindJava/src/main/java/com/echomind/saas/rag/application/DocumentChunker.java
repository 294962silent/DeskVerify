package com.echomind.saas.rag.application;

import java.util.List;

public interface DocumentChunker {
    List<ChunkDraft> chunk(String normalizedText);
}
