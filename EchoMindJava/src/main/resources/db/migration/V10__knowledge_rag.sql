create table knowledge_document (
    tenant_id varchar(64) not null,
    document_id varchar(128) not null,
    title varchar(255) not null,
    status varchar(32) not null,
    current_version int null,
    created_by_user_id varchar(128) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    updated_at timestamp(6) not null default current_timestamp(6)
        on update current_timestamp(6),
    primary key (tenant_id, document_id),
    constraint chk_knowledge_document_status check (
        status in ('ACTIVE', 'RETIRED')
    ),
    index idx_knowledge_document_status (tenant_id, status, updated_at)
);

create table knowledge_document_version (
    tenant_id varchar(64) not null,
    document_id varchar(128) not null,
    version_number int not null,
    status varchar(32) not null,
    source_checksum char(64) not null,
    normalized_text mediumtext not null,
    chunk_count int not null default 0,
    embedding_model varchar(80) not null,
    created_by_user_id varchar(128) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    indexed_at timestamp(6) null,
    published_at timestamp(6) null,
    failure_reason varchar(1000) null,
    primary key (tenant_id, document_id, version_number),
    constraint fk_knowledge_version_document
        foreign key (tenant_id, document_id)
        references knowledge_document(tenant_id, document_id),
    constraint uk_knowledge_version_checksum
        unique (tenant_id, document_id, source_checksum),
    constraint chk_knowledge_version_status check (
        status in ('DRAFT', 'INDEXING', 'PUBLISHED', 'FAILED', 'RETIRED')
    ),
    constraint chk_knowledge_version_number check (version_number > 0),
    constraint chk_knowledge_chunk_count check (chunk_count >= 0)
);

create table knowledge_chunk (
    tenant_id varchar(64) not null,
    chunk_id varchar(128) not null,
    document_id varchar(128) not null,
    version_number int not null,
    chunk_index int not null,
    document_title varchar(255) not null,
    content text not null,
    page_number int null,
    section_title varchar(255) null,
    heading_path varchar(1000) null,
    char_start int not null,
    char_end int not null,
    checksum char(64) not null,
    visibility varchar(32) not null,
    customer_id varchar(128) null,
    embedding_json json not null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (tenant_id, chunk_id),
    constraint fk_knowledge_chunk_version
        foreign key (tenant_id, document_id, version_number)
        references knowledge_document_version(tenant_id, document_id, version_number),
    constraint uk_knowledge_chunk_position
        unique (tenant_id, document_id, version_number, chunk_index),
    constraint chk_knowledge_chunk_index check (chunk_index >= 0),
    constraint chk_knowledge_chunk_range check (
        char_start >= 0 and char_end > char_start
    ),
    constraint chk_knowledge_chunk_visibility check (
        (visibility = 'TENANT' and customer_id is null)
        or
        (visibility = 'CUSTOMER' and customer_id is not null)
    ),
    fulltext key ft_knowledge_chunk_content (
        content, document_title, section_title
    ) with parser ngram,
    index idx_knowledge_chunk_active (
        tenant_id, document_id, version_number, chunk_index
    ),
    index idx_knowledge_chunk_acl (
        tenant_id, visibility, customer_id
    )
);
