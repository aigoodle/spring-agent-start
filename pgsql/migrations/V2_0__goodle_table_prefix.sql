-- Unify all spring-agent owned tables under the goodle_ prefix.
-- Safe to re-run: an entry is renamed only when the old table exists and the
-- target table does not. A target collision is surfaced instead of merging data.
DO $$
DECLARE
    item TEXT[];
    mappings TEXT[][] := ARRAY[
        ARRAY['agent_memories', 'goodle_memories'],
        ARRAY['agent_model', 'goodle_model'],
        ARRAY['agent_model_provider', 'goodle_model_provider'],
        ARRAY['agent_predefined_model', 'goodle_predefined_model'],
        ARRAY['agent_prompt_template', 'goodle_prompt_template'],
        ARRAY['agent_provider_credential', 'goodle_provider_credential'],
        ARRAY['agent_provider_model_setting', 'goodle_provider_model_setting'],
        ARRAY['agent_run_events', 'goodle_run_events'],
        ARRAY['agent_runs', 'goodle_runs'],
        ARRAY['agent_tenant_default_model', 'goodle_tenant_default_model'],
        ARRAY['api_tokens', 'goodle_api_tokens'],
        ARRAY['app_annotation_settings', 'goodle_app_annotation_settings'],
        ARRAY['app_annotations', 'goodle_app_annotations'],
        ARRAY['app_model_configs', 'goodle_app_model_configs'],
        ARRAY['app_sites', 'goodle_app_sites'],
        ARRAY['app_triggers', 'goodle_app_triggers'],
        ARRAY['apps', 'goodle_apps'],
        ARRAY['conversations', 'goodle_conversations'],
        ARRAY['dataset', 'goodle_dataset'],
        ARRAY['dataset_query', 'goodle_dataset_query'],
        ARRAY['document_ingest_queue', 'goodle_document_ingest_queue'],
        ARRAY['document_segments', 'goodle_document_segments'],
        ARRAY['documents', 'goodle_documents'],
        ARRAY['embeddings', 'goodle_embeddings'],
        ARRAY['llm_calls', 'goodle_llm_calls'],
        ARRAY['messages', 'goodle_messages'],
        ARRAY['tag_bindings', 'goodle_tag_bindings'],
        ARRAY['tags', 'goodle_tags'],
        ARRAY['trigger_invocations', 'goodle_trigger_invocations'],
        ARRAY['workflow_runs', 'goodle_workflow_runs'],
        ARRAY['workflows', 'goodle_workflows']
    ];
BEGIN
    FOREACH item SLICE 1 IN ARRAY mappings LOOP
        IF to_regclass(format('public.%I', item[1])) IS NOT NULL THEN
            IF to_regclass(format('public.%I', item[2])) IS NOT NULL THEN
                RAISE EXCEPTION 'Cannot rename %.%: target %.% already exists',
                    'public', item[1], 'public', item[2];
            END IF;
            EXECUTE format('ALTER TABLE public.%I RENAME TO %I', item[1], item[2]);
        END IF;
    END LOOP;
END
$$;
