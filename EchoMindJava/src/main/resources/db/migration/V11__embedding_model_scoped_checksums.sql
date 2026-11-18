alter table knowledge_document_version
    drop index uk_knowledge_version_checksum,
    add constraint uk_knowledge_version_checksum_model
        unique (tenant_id, document_id, source_checksum, embedding_model);
