export function createRagClient(request) {
  if (typeof request !== 'function') {
    throw new TypeError('request must be a function')
  }

  return {
    createDocument({ title, content, visibility = 'TENANT', customerId = '' }) {
      return request('/api/v1/knowledge-documents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title,
          content,
          visibility,
          customer_id: visibility === 'CUSTOMER' ? customerId : null
        })
      })
    },
    addVersion(documentId, { content, visibility = 'TENANT', customerId = '' }) {
      return request(`/api/v1/knowledge-documents/${encodeURIComponent(documentId)}/versions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          content,
          visibility,
          customer_id: visibility === 'CUSTOMER' ? customerId : null
        })
      })
    },
    publish(documentId, versionNumber) {
      return request(`/api/v1/knowledge-documents/${encodeURIComponent(documentId)}/publish`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ version_number: versionNumber })
      })
    },
    versions(documentId) {
      return request(`/api/v1/knowledge-documents/${encodeURIComponent(documentId)}/versions`)
    },
    search(query, topK = 5) {
      return request('/api/v1/knowledge/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, top_k: topK })
      })
    },
    stats() {
      return request('/api/v1/knowledge/stats')
    }
  }
}

export function normalizeRagSearch(raw) {
  return {
    query: raw?.query || '',
    hits: Array.isArray(raw?.hits) ? raw.hits.map(normalizeHit) : [],
    citations: Array.isArray(raw?.citations) ? raw.citations : [],
    degradedModes: raw?.degraded_modes || raw?.degradedModes || [],
    latencyMs: Number(raw?.latency_ms ?? raw?.latencyMs ?? 0)
  }
}

export function citationLabel(citation) {
  const location = citation.section_title || citation.heading_path || '正文'
  return `${citation.document_title} · v${citation.version_number} · ${location}`
}

export function canManageKnowledge(roles) {
  return new Set((roles || []).map((role) => String(role).toUpperCase()))
    .has('TENANT_ADMIN')
}

function normalizeHit(hit) {
  return {
    ...hit,
    lexicalScore: Number(hit?.lexical_score ?? hit?.lexicalScore ?? 0),
    vectorScore: Number(hit?.vector_score ?? hit?.vectorScore ?? 0),
    rrfScore: Number(hit?.rrf_score ?? hit?.rrfScore ?? 0),
    rerankScore: Number(hit?.rerank_score ?? hit?.rerankScore ?? 0)
  }
}
