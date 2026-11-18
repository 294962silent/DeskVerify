import assert from 'node:assert/strict'
import test from 'node:test'
import {
  canManageKnowledge,
  citationLabel,
  createRagClient,
  normalizeChatRagFields,
  normalizeRagSearch
} from '../lib/rag.js'

test('rag client sends snake_case version and publish payloads', async () => {
  const calls = []
  const client = createRagClient(async (path, options = {}) => {
    calls.push({ path, options })
    return { ok: true }
  })

  await client.createDocument({
    title: 'Policy',
    content: 'Content',
    visibility: 'CUSTOMER',
    customerId: 'customer-001'
  })
  await client.addVersion('doc/a', { content: 'Version', visibility: 'TENANT' })
  await client.publish('doc/a', 3)
  await client.search('席位多久生效', 4)

  assert.deepEqual(JSON.parse(calls[0].options.body), {
    title: 'Policy',
    content: 'Content',
    visibility: 'CUSTOMER',
    customer_id: 'customer-001'
  })
  assert.equal(calls[1].path, '/api/v1/knowledge-documents/doc%2Fa/versions')
  assert.deepEqual(JSON.parse(calls[2].options.body), { version_number: 3 })
  assert.deepEqual(JSON.parse(calls[3].options.body), {
    query: '席位多久生效',
    top_k: 4
  })
})

test('search normalization preserves scores degraded modes and citations', () => {
  const normalized = normalizeRagSearch({
    query: '续费',
    hits: [{
      chunk_id: 'chunk-1',
      lexical_score: 0.8,
      vector_score: 0.6,
      rrf_score: 0.02,
      rerank_score: 0.7
    }],
    citations: [{
      citation_id: 'citation-1',
      document_title: '续费政策',
      version_number: 2,
      section_title: '席位'
    }],
    degraded_modes: ['lexical_unavailable'],
    latency_ms: 18
  })

  assert.equal(normalized.hits[0].lexicalScore, 0.8)
  assert.equal(normalized.hits[0].rerankScore, 0.7)
  assert.deepEqual(normalized.degradedModes, ['lexical_unavailable'])
  assert.equal(normalized.latencyMs, 18)
  assert.equal(
    citationLabel(normalized.citations[0]),
    '续费政策 · v2 · 席位'
  )
})

test('knowledge management is restricted to tenant admins', () => {
  assert.equal(canManageKnowledge(['CUSTOMER']), false)
  assert.equal(canManageKnowledge(['SUPPORT_AGENT']), false)
  assert.equal(canManageKnowledge(['TENANT_ADMIN']), true)
})

test('chat RAG fields preserve citation chain and degraded modes', () => {
  const normalized = normalizeChatRagFields({
    citations: [{
      citation_id: 'citation-1',
      document_title: '续费政策',
      version_number: 2,
      section_title: '席位',
      heading_path: '套餐 > 席位',
      char_start: 10,
      char_end: 40
    }],
    retrieval_degraded_modes: ['lexical_unavailable']
  })

  assert.equal(normalized.citations[0].citationId, 'citation-1')
  assert.equal(normalized.citations[0].documentTitle, '续费政策')
  assert.equal(normalized.citations[0].headingPath, '套餐 > 席位')
  assert.deepEqual(normalized.retrievalDegradedModes, ['lexical_unavailable'])
})
