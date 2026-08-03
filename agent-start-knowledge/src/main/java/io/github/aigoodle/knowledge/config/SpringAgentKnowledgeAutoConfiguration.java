package io.github.aigoodle.knowledge.config;

import io.github.aigoodle.knowledge.async.DocumentIngestionListener;
import io.github.aigoodle.knowledge.async.DocumentIngestionQueue;
import io.github.aigoodle.knowledge.async.DocumentIngestionRunner;
import io.github.aigoodle.knowledge.async.KnowledgeQueueNames;
import io.github.aigoodle.knowledge.async.RabbitDocumentIngestionQueue;
import io.github.aigoodle.knowledge.async.SyncDocumentIngestionQueue;
import io.github.aigoodle.knowledge.chunk.Chunker;
import io.github.aigoodle.knowledge.chunk.ChunkerRegistry;
import io.github.aigoodle.knowledge.index.IndexingService;
import io.github.aigoodle.knowledge.index.JdbcVectorStoreFactory;
import io.github.aigoodle.knowledge.index.VectorStoreFactory;
import io.github.aigoodle.knowledge.index.VectorStoreManager;
import io.github.aigoodle.knowledge.mapper.DatasetMapper;
import io.github.aigoodle.knowledge.mapper.DocumentIngestQueueMapper;
import io.github.aigoodle.knowledge.mapper.KnowledgeDocumentMapper;
import io.github.aigoodle.knowledge.mapper.SegmentMapper;
import io.github.aigoodle.knowledge.reader.DocumentExtractor;
import io.github.aigoodle.knowledge.reader.DocumentReader;
import io.github.aigoodle.knowledge.reader.DocumentReaderRegistry;
import io.github.aigoodle.knowledge.reader.HtmlDocumentReader;
import io.github.aigoodle.knowledge.reader.MarkdownDocumentReader;
import io.github.aigoodle.knowledge.reader.TextDocumentReader;
import io.github.aigoodle.knowledge.reader.TikaDocumentReader;
import io.github.aigoodle.knowledge.rerank.ModelReranker;
import io.github.aigoodle.knowledge.rerank.NoopReranker;
import io.github.aigoodle.knowledge.rerank.Reranker;
import io.github.aigoodle.knowledge.rerank.RerankerRegistry;
import io.github.aigoodle.knowledge.rerank.WeightedReranker;
import io.github.aigoodle.knowledge.rerank.WeightedRerankerSettings;
import io.github.aigoodle.knowledge.retrieve.HybridRetriever;
import io.github.aigoodle.knowledge.service.DatasetService;
import io.github.aigoodle.knowledge.service.KnowledgeService;
import io.github.aigoodle.model.config.SpringAgentModelAutoConfiguration;
import io.github.aigoodle.model.service.ModelService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for the knowledge module. Builds on the model module
 * (embedding models come from {@link ModelService}) and wires the full RAG pipeline:
 * chunking, indexing, vector store management, hybrid retrieval and reranking.
 */
@AutoConfiguration(after = SpringAgentModelAutoConfiguration.class)
@MapperScan("io.github.aigoodle.knowledge.mapper")
public class SpringAgentKnowledgeAutoConfiguration {

    // ------------------------------------------------------------- chunkers

    @Bean
    @ConditionalOnMissingBean
    public ChunkerRegistry chunkerRegistry(ObjectProvider<Chunker> customChunkers) {
        return new ChunkerRegistry(customChunkers.orderedStream().toList());
    }

    // -------------------------------------------------------- document readers

    @Bean
    @ConditionalOnMissingBean(name = "textDocumentReader")
    public TextDocumentReader textDocumentReader() {
        return new TextDocumentReader();
    }

    @Bean
    @ConditionalOnMissingBean(name = "markdownDocumentReader")
    public MarkdownDocumentReader markdownDocumentReader() {
        return new MarkdownDocumentReader();
    }

    @Bean
    @ConditionalOnMissingBean(name = "htmlDocumentReader")
    public HtmlDocumentReader htmlDocumentReader() {
        return new HtmlDocumentReader();
    }

    @Bean
    @ConditionalOnMissingBean(name = "tikaDocumentReader")
    public TikaDocumentReader tikaDocumentReader() {
        return new TikaDocumentReader();
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentReaderRegistry documentReaderRegistry(ObjectProvider<DocumentReader> readers) {
        List<DocumentReader> ordered = new ArrayList<>(readers.orderedStream().toList());
        // Ensure the Tika fallback lands last so specific readers get first crack.
        ordered.sort((a, b) -> {
            boolean aTika = TikaDocumentReader.NAME.equalsIgnoreCase(a.getName());
            boolean bTika = TikaDocumentReader.NAME.equalsIgnoreCase(b.getName());
            return Boolean.compare(aTika, bTika);
        });
        return new DocumentReaderRegistry(ordered);
    }

    @Bean
    @ConditionalOnMissingBean
    public DocumentExtractor documentExtractor(DocumentReaderRegistry registry) {
        return new DocumentExtractor(registry);
    }

    // ------------------------------------------------------------- rerankers

    @Bean
    @ConditionalOnMissingBean(name = "noopReranker")
    public NoopReranker noopReranker() {
        return new NoopReranker();
    }

    @Bean
    @ConditionalOnMissingBean(name = "weightedReranker")
    public WeightedReranker weightedReranker(
            @Value("${spring-agent.knowledge.reranker.weighted.vector-weight:0.6}") double vectorWeight,
            @Value("${spring-agent.knowledge.reranker.weighted.keyword-weight:0.3}") double keywordWeight,
            @Value("${spring-agent.knowledge.reranker.weighted.length-weight:0.1}") double lengthWeight,
            @Value("${spring-agent.knowledge.reranker.weighted.ideal-length:400}") int idealLength) {
        return new WeightedReranker(new WeightedRerankerSettings(
                vectorWeight, keywordWeight, lengthWeight, idealLength));
    }

    @Bean
    @ConditionalOnMissingBean(name = "modelReranker")
    public ModelReranker modelReranker(ModelService modelService,
                                       @Value("${spring-agent.knowledge.reranker.model.model-id:}") String modelId) {
        return new ModelReranker(modelService, modelId);
    }

    @Bean
    @ConditionalOnMissingBean
    public RerankerRegistry rerankerRegistry(ObjectProvider<Reranker> rerankers,
                                             NoopReranker fallback) {
        return new RerankerRegistry(rerankers.orderedStream().toList(), fallback);
    }

    // ---------------------------------------------------------- vector stores

    /**
     * Persist embeddings in the relational database when
     * {@code spring-agent.knowledge.vector-store=jdbc}. Otherwise the in-memory store is used.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring-agent.knowledge", name = "vector-store", havingValue = "jdbc")
    @ConditionalOnMissingBean(VectorStoreFactory.class)
    public VectorStoreFactory jdbcVectorStoreFactory(JdbcTemplate jdbcTemplate,
            @Value("${spring-agent.knowledge.vector-table:embeddings}") String table) {
        return new JdbcVectorStoreFactory(jdbcTemplate, table);
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorStoreManager vectorStoreManager(ModelService modelService,
                                                 ObjectProvider<VectorStoreFactory> factory) {
        return new VectorStoreManager(modelService, factory.getIfAvailable());
    }

    // ------------------------------------------------------------- pipeline

    @Bean
    @ConditionalOnMissingBean
    public IndexingService indexingService(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager) {
        return new IndexingService(segmentMapper, vectorStoreManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public HybridRetriever hybridRetriever(SegmentMapper segmentMapper, VectorStoreManager vectorStoreManager,
                                           RerankerRegistry rerankerRegistry) {
        return new HybridRetriever(segmentMapper, vectorStoreManager, rerankerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public DatasetService datasetService(DatasetMapper datasetMapper) {
        return new DatasetService(datasetMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeService knowledgeService(DatasetService datasetService,
                                             KnowledgeDocumentMapper documentMapper,
                                             ChunkerRegistry chunkerRegistry,
                                             IndexingService indexingService,
                                             HybridRetriever retriever,
                                             DocumentExtractor extractor,
                                             io.github.aigoodle.knowledge.mapper.HitTestingLogMapper hitTestingLogMapper,
                                             ObjectProvider<DocumentIngestionQueue> ingestionQueue,
                                             ObjectProvider<DocumentIngestQueueMapper> queueMapper) {
        KnowledgeService service = new KnowledgeService(datasetService, documentMapper, chunkerRegistry,
                indexingService, retriever, extractor);
        service.setHitTestingLogMapper(hitTestingLogMapper);
        // Wire async only when both the queue and its sidecar mapper are on
        // the classpath (they come as a pair from the async auto-config below).
        DocumentIngestionQueue q = ingestionQueue.getIfAvailable();
        DocumentIngestQueueMapper m = queueMapper.getIfAvailable();
        if (q != null && m != null) {
            service.setIngestionQueue(q, m);
        }
        return service;
    }

    // ============================================================
    //  Async ingestion pipeline (opt-in via
    //  spring-agent.knowledge.async.enabled=true).
    //
    //  Two modes:
    //   * "sync" (default when async is on but no broker present)
    //       — SyncDocumentIngestionQueue: background thread pool inside the
    //         JVM. Still gets the upload-returns-immediately win, without
    //         requiring an external broker.
    //   * "rabbit" — RabbitDocumentIngestionQueue + @RabbitListener worker.
    //         Durable at-least-once semantics + separate worker scaling.
    // ============================================================

    /**
     * Runner is the pure "extract → clean → chunk → index" pipeline; both
     * queue implementations delegate to it. Always registered when async is
     * on; harmless idle bean when it's off.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring-agent.knowledge.async", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public DocumentIngestionRunner documentIngestionRunner(DatasetService datasetService,
                                                           KnowledgeDocumentMapper documentMapper,
                                                           DocumentIngestQueueMapper queueMapper,
                                                           ChunkerRegistry chunkerRegistry,
                                                           IndexingService indexingService) {
        return new DocumentIngestionRunner(datasetService, documentMapper, queueMapper,
                chunkerRegistry, indexingService);
    }

    /**
     * In-memory queue — active when async is on but the host hasn't
     * configured RabbitMQ (no {@code spring.rabbitmq.host} → no
     * {@code RabbitTemplate} bean → we fall back to this). Thread count via
     * {@code spring-agent.knowledge.async.worker-threads}, default 4.
     */
    @Bean
    @ConditionalOnProperty(prefix = "spring-agent.knowledge.async", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "documentIngestionQueue")
    @org.springframework.context.annotation.Conditional(NoRabbitTemplateCondition.class)
    public DocumentIngestionQueue syncDocumentIngestionQueue(DocumentIngestionRunner runner,
            @Value("${spring-agent.knowledge.async.worker-threads:4}") int workerThreads) {
        return new SyncDocumentIngestionQueue(runner, workerThreads);
    }

    /**
     * RabbitMQ-backed queue + topology + listener — active when async is on
     * AND the host has a {@code RabbitTemplate} bean (starter-amqp auto-config
     * picked up {@code spring.rabbitmq.host}).
     */
    @org.springframework.context.annotation.Configuration
    @ConditionalOnProperty(prefix = "spring-agent.knowledge.async", name = "enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
            org.springframework.amqp.rabbit.core.RabbitTemplate.class)
    public static class RabbitAsyncConfig {

        @Bean(name = "documentIngestionQueue")
        @ConditionalOnMissingBean(name = "documentIngestionQueue")
        public DocumentIngestionQueue documentIngestionQueue(
                org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate) {
            return new RabbitDocumentIngestionQueue(rabbitTemplate);
        }

        @Bean
        @ConditionalOnMissingBean
        public DocumentIngestionListener documentIngestionListener(DocumentIngestionRunner runner) {
            return new DocumentIngestionListener(runner);
        }

        /**
         * JSON converter so {@link io.github.aigoodle.knowledge.async.DocumentIngestionTask}
         * ships as plain JSON instead of Java serialisation (portable + safer).
         */
        @Bean
        @ConditionalOnMissingBean(org.springframework.amqp.support.converter.Jackson2JsonMessageConverter.class)
        public org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
            return new org.springframework.amqp.support.converter.Jackson2JsonMessageConverter();
        }

        /**
         * Attach the JSON converter to the RabbitTemplate and to the listener
         * container factory so publish and consume both use it.
         */
        @Bean
        public org.springframework.beans.factory.config.BeanPostProcessor knowledgeRabbitTemplateConverterPostProcessor(
                org.springframework.amqp.support.converter.Jackson2JsonMessageConverter converter) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof org.springframework.amqp.rabbit.core.RabbitTemplate rt) {
                        rt.setMessageConverter(converter);
                    }
                    if (bean instanceof org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory f) {
                        f.setMessageConverter(converter);
                    }
                    return bean;
                }
            };
        }

        // Topology — main exchange/queue with DLX pointing at the DLQ.
        // Messages that Spring rejects with AmqpRejectAndDontRequeueException
        // land on the DLQ automatically.

        @Bean
        public org.springframework.amqp.core.DirectExchange kbDocumentExchange() {
            return new org.springframework.amqp.core.DirectExchange(KnowledgeQueueNames.EXCHANGE, true, false);
        }

        @Bean
        public org.springframework.amqp.core.DirectExchange kbDocumentDlqExchange() {
            return new org.springframework.amqp.core.DirectExchange(KnowledgeQueueNames.DLQ_EXCHANGE, true, false);
        }

        @Bean
        public org.springframework.amqp.core.Queue kbDocumentIngestQueue() {
            return org.springframework.amqp.core.QueueBuilder.durable(KnowledgeQueueNames.QUEUE)
                    .withArgument("x-dead-letter-exchange", KnowledgeQueueNames.DLQ_EXCHANGE)
                    .withArgument("x-dead-letter-routing-key", KnowledgeQueueNames.DLQ_ROUTING_KEY)
                    .build();
        }

        @Bean
        public org.springframework.amqp.core.Queue kbDocumentIngestDlqQueue() {
            return org.springframework.amqp.core.QueueBuilder.durable(KnowledgeQueueNames.DLQ_QUEUE).build();
        }

        @Bean
        public org.springframework.amqp.core.Binding kbDocumentIngestBinding(
                org.springframework.amqp.core.Queue kbDocumentIngestQueue,
                org.springframework.amqp.core.DirectExchange kbDocumentExchange) {
            return org.springframework.amqp.core.BindingBuilder.bind(kbDocumentIngestQueue)
                    .to(kbDocumentExchange).with(KnowledgeQueueNames.ROUTING_KEY);
        }

        @Bean
        public org.springframework.amqp.core.Binding kbDocumentIngestDlqBinding(
                org.springframework.amqp.core.Queue kbDocumentIngestDlqQueue,
                org.springframework.amqp.core.DirectExchange kbDocumentDlqExchange) {
            return org.springframework.amqp.core.BindingBuilder.bind(kbDocumentIngestDlqQueue)
                    .to(kbDocumentDlqExchange).with(KnowledgeQueueNames.DLQ_ROUTING_KEY);
        }
    }

    /**
     * Selector: registers the sync queue only when no RabbitTemplate is on
     * the classpath (or no bean present). Kept as a static inner class so
     * ConditionalOnMissingBean is deferred until Spring context refresh.
     */
    static class NoRabbitTemplateCondition
            extends org.springframework.boot.autoconfigure.condition.NoneNestedConditions {
        NoRabbitTemplateCondition() {
            super(org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(
                org.springframework.amqp.rabbit.core.RabbitTemplate.class)
        static class HasRabbit { }
    }
}
