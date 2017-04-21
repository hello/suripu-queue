package com.hello.suripu.queue.cli;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.hello.suripu.api.notifications.PushNotification;
import com.hello.suripu.core.ObjectGraphRoot;
import com.hello.suripu.core.algorithmintegration.NeuralNetEndpoint;
import com.hello.suripu.core.configuration.DynamoDBTableName;
import com.hello.suripu.core.db.AccountDAO;
import com.hello.suripu.core.db.AccountDAOImpl;
import com.hello.suripu.core.db.CalibrationDAO;
import com.hello.suripu.core.db.CalibrationDynamoDB;
import com.hello.suripu.core.db.DefaultModelEnsembleDAO;
import com.hello.suripu.core.db.DefaultModelEnsembleFromS3;
import com.hello.suripu.core.db.DeviceDataDAODynamoDB;
import com.hello.suripu.core.db.DeviceReadDAO;
import com.hello.suripu.core.db.FeatureExtractionModelsDAO;
import com.hello.suripu.core.db.FeatureExtractionModelsDAODynamoDB;
import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.db.FeedbackReadDAO;
import com.hello.suripu.core.db.HistoricalPairingDAO;
import com.hello.suripu.core.db.MainEventTimesDynamoDB;
import com.hello.suripu.core.db.OnlineHmmModelsDAO;
import com.hello.suripu.core.db.OnlineHmmModelsDAODynamoDB;
import com.hello.suripu.core.db.PairingDAO;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SenseDataDAODynamoDB;
import com.hello.suripu.core.db.SleepScoreParametersDAO;
import com.hello.suripu.core.db.SleepScoreParametersDynamoDB;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.TimeZoneHistoryDAODynamoDB;
import com.hello.suripu.core.db.UserTimelineTestGroupDAO;
import com.hello.suripu.core.db.UserTimelineTestGroupDAOImpl;
import com.hello.suripu.core.db.colors.SenseColorDAO;
import com.hello.suripu.core.db.colors.SenseColorDAOSQLImpl;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.db.util.PostgresIntegerArrayArgumentFactory;
import com.hello.suripu.core.notifications.PushNotificationEvent;
import com.hello.suripu.core.notifications.sender.NotificationSender;
import com.hello.suripu.core.util.AlgorithmType;
import com.hello.suripu.coredropwizard.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.coredropwizard.clients.TaimurainHttpClient;
import com.hello.suripu.coredropwizard.configuration.S3BucketConfiguration;
import com.hello.suripu.coredropwizard.configuration.TimelineAlgorithmConfiguration;
import com.hello.suripu.coredropwizard.db.SleepHmmDAODynamoDB;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessor;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessorV3;
import com.hello.suripu.coredropwizard.timeline.TimelineProcessor;
import com.hello.suripu.queue.configuration.SQSConfiguration;
import com.hello.suripu.queue.configuration.SuripuQueueConfiguration;
import com.hello.suripu.queue.modules.RolloutQueueModule;
import com.hello.suripu.queue.timeline.TimelineGenerator;
import com.hello.suripu.queue.timeline.TimelineQueueProcessor;
import com.librato.rollout.RolloutAdapter;
import com.librato.rollout.RolloutClient;
import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.jdbi.ImmutableListContainerFactory;
import io.dropwizard.jdbi.ImmutableSetContainerFactory;
import io.dropwizard.jdbi.OptionalContainerFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Duration;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

// docs: http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/Welcome.html
public class TimelineQueueWorkerCommand extends ConfiguredCommand<SuripuQueueConfiguration> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimelineQueueWorkerCommand.class);
    private Boolean isRunning = false;
    private ExecutorService executor;

    private static class NoopSender implements NotificationSender {

        @Override
        public void send(PushNotificationEvent pushNotificationEvent) {

        }

        @Override
        public void sendRaw(String partitionKey, byte[] protobuf) {

        }

        @Override
        public List<PushNotification.UserPushNotification> sendBatch(List<PushNotification.UserPushNotification> notifications) {
            return Lists.newArrayList();
        }
    }

    private static class NoopFlipper implements RolloutAdapter {

        @Override
        public boolean userFeatureActive(String feature, long userId, List<String> userGroups) {
            return false;
        }

        @Override
        public boolean deviceFeatureActive(String feature, String deviceId, List<String> userGroups) {
            return false;
        }
    }

    public TimelineQueueWorkerCommand() {
        super("timeline_generator", "generate timeline");

    }

    @Override
    public void configure(Subparser subparser) {
        super.configure(subparser);
        subparser.addArgument("--task")
                .nargs("?")
                .required(true)
                .help("task to perform, send or process queue messages");

        // for sending messages
        subparser.addArgument("--num_msg")
                .nargs("?")
                .required(false)
                .help("number of messages to send");

        subparser.addArgument("--account")
                .nargs("?")
                .required(false)
                .help("number of messages to send");

    }

    @Override
    protected void run(Bootstrap<SuripuQueueConfiguration> bootstrap, Namespace namespace, SuripuQueueConfiguration configuration) throws Exception{
        final String task = namespace.getString("task");

        // setup SQS connection
        final AWSCredentialsProvider provider = new DefaultAWSCredentialsProviderChain();

        final SQSConfiguration sqsConfiguration = configuration.getSqsConfiguration();
        final int maxConnections = sqsConfiguration.getSqsMaxConnections();
        final AmazonSQSAsync sqsAsync = new AmazonSQSAsyncClient(provider, new ClientConfiguration().withMaxConnections(maxConnections).withConnectionTimeout(500));
        final AmazonSQSAsync sqs = new AmazonSQSBufferedAsyncClient(sqsAsync);

        final Region region = Region.getRegion(Regions.US_EAST_1);
        sqs.setRegion(region);

        final Optional<String> optionalSqsQueueUrl = TimelineQueueProcessor.getSQSQueueURL(sqs, sqsConfiguration.getSqsQueueName());
        if (!optionalSqsQueueUrl.isPresent()) {
            LOGGER.error("error=no-sqs-found queue_name={}", sqsConfiguration.getSqsQueueName());
            throw new Exception("Invalid queue name");
        }

        final String sqsQueueUrl = optionalSqsQueueUrl.get();

        final TimelineQueueProcessor queueProcessor = new TimelineQueueProcessor(sqsQueueUrl, sqs, sqsConfiguration);
        final Environment environment = new Environment(bootstrap.getApplication().getName(),
                bootstrap.getObjectMapper(),
                bootstrap.getValidatorFactory().getValidator(),
                bootstrap.getMetricRegistry(),
                bootstrap.getClassLoader());


        if (task.equalsIgnoreCase("send")) {
            // producer -- debugging, create 10 messages for testing
            Integer numMessages = 30;
            Long accountId = 1310L;

            if (namespace.getString("num_msg") != null) {
                numMessages = Integer.valueOf(namespace.getString("num_msg"));
            }

            if (namespace.getString("account") != null) {
                accountId = Long.valueOf(namespace.getString("account"));
            }

            queueProcessor.sendMessages(accountId, numMessages);

        } else {
            // consumer
            final int numConsumerThreads = configuration.getNumConsumerThreads();
            executor = environment.lifecycle().executorService("timeline_queue")
                    .minThreads(numConsumerThreads)
                    .maxThreads(numConsumerThreads)
                    .keepAliveTime(Duration.minutes(2L))
                    .build();
            isRunning = true;
            processMessages(environment, queueProcessor, provider, configuration);
        }
    }


    /**
     * Main queue worker function
     * @param queueProcessor - send, receive, encode and decode queue messages
     * @param provider - aws credentials
     * @param configuration - config object
     * @throws Exception
     */

    private void processMessages(final Environment environment,
                                 final TimelineQueueProcessor queueProcessor,
                                 final AWSCredentialsProvider provider,
                                 final SuripuQueueConfiguration configuration) throws Exception {
        // setup rollout module
        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(provider, configuration.dynamoDBConfiguration());
        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final FeatureStore featureStore = new FeatureStore(featuresDynamoDBClient,
                configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.FEATURES), "prod");

        final RolloutQueueModule module = new RolloutQueueModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(module);

        // set up metrics
        if (configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.getDebug()) ? "dev" : "prod";
            final String prefix = String.format("%s.%s.suripu-queue", apiKey, env);

            final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHostName, 2003));
            final GraphiteReporter reporter = GraphiteReporter.forRegistry(environment.metrics())
                    .prefixedWith(prefix)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .filter(MetricFilter.ALL)
                    .build(graphite);
            reporter.start(interval, TimeUnit.SECONDS);

            LOGGER.info("Metrics enabled.");
        } else {
            LOGGER.warn("Metrics not enabled.");
        }

        final MetricRegistry metrics = environment.metrics();

        final Class klass = TimelineQueueWorkerCommand.class;
        final Meter messagesProcessed = metrics.meter(MetricRegistry.name(klass, "processed", "messages-processed"));
        final Meter messagesReceived = metrics.meter(MetricRegistry.name(klass, "received", "messages-received"));
        final Meter messagesDeleted = metrics.meter(MetricRegistry.name(klass, "deleted", "messages-deleted"));
        final Meter validSleepScore = metrics.meter(MetricRegistry.name(klass, "ok-sleep-score", "valid-score"));
        final Meter invalidSleepScore = metrics.meter(MetricRegistry.name(klass, "invalid-sleep-score", "invalid-score"));
        final Meter noTimeline = metrics.meter(MetricRegistry.name(klass, "timeline-fail", "fail-to-created"));

        final InstrumentedTimelineProcessor timelineProcessorV2 = createTimelineProcessor(environment, provider, configuration);
        final InstrumentedTimelineProcessorV3 timelineProcessorV3 = createTimelineProcessorV3(environment, provider, configuration);
        final TimelineProcessor timelineProcessor = TimelineProcessor.createTimelineProcessors(timelineProcessorV2, timelineProcessorV3);
        int numEmptyQueueIterations = 0;

        do {
            final List<TimelineQueueProcessor.TimelineMessage> messages = queueProcessor.receiveMessages();

            messagesReceived.mark(messages.size());

            final List<Future<TimelineQueueProcessor.TimelineMessage>> futures = Lists.newArrayListWithCapacity(messages.size());


            if (!messages.isEmpty()) {
                for (final TimelineQueueProcessor.TimelineMessage message : messages) {
                    final TimelineGenerator generator = new TimelineGenerator(timelineProcessor, message, new NoopSender(), new RolloutClient(new NoopFlipper()));
                    final Future<TimelineQueueProcessor.TimelineMessage> future = executor.submit(generator);
                    futures.add(future);
                }

                final List<DeleteMessageBatchRequestEntry> processedHandlers = Lists.newArrayList();
                for (final Future<TimelineQueueProcessor.TimelineMessage> future : futures) {
                    final TimelineQueueProcessor.TimelineMessage processed = future.get();

                    processedHandlers.add(new DeleteMessageBatchRequestEntry(processed.messageId, processed.messageHandler));
                    if (processed.sleepScore.isPresent()) {
                        if (processed.sleepScore.get() > 0) {
                            validSleepScore.mark();
                        } else {
                            invalidSleepScore.mark();
                        }
                    } else {
                        noTimeline.mark();
                    }
                }

                if (!processedHandlers.isEmpty()) {
                    LOGGER.debug("action=delete-messages num={}", processedHandlers.size());
                    messagesProcessed.mark(processedHandlers.size());
                    final int deleted = queueProcessor.deleteMessages(processedHandlers);
                    messagesDeleted.mark(deleted);
                }

                numEmptyQueueIterations = 0;

            } else {
                numEmptyQueueIterations++;
                LOGGER.debug("action=empty-iteration value={}", numEmptyQueueIterations);
            }

        } while (isRunning);
    }

    private InstrumentedTimelineProcessor createTimelineProcessor(final Environment environment,
                                                      final AWSCredentialsProvider provider,
                                                      final SuripuQueueConfiguration config)throws Exception {

        final DBIFactory factory = new DBIFactory();
        final DBI commonDB = factory.build(environment, config.getCommonDB(), "commonDB");

        commonDB.registerArgumentFactory(new JodaArgumentFactory());
        commonDB.registerContainerFactory(new OptionalContainerFactory());
        commonDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());
        commonDB.registerContainerFactory(new ImmutableListContainerFactory());
        commonDB.registerContainerFactory(new ImmutableSetContainerFactory());

        final DeviceReadDAO deviceDAO = commonDB.onDemand(DeviceReadDAO.class);
        final FeedbackReadDAO feedbackDAO = commonDB.onDemand(FeedbackReadDAO.class);
        final AccountDAO accountDAO = commonDB.onDemand(AccountDAOImpl.class);
        final SenseColorDAO senseColorDAO = commonDB.onDemand(SenseColorDAOSQLImpl.class);
        final UserTimelineTestGroupDAO userTimelineTestGroupDAO = commonDB.onDemand(UserTimelineTestGroupDAOImpl.class);

        final ClientConfiguration clientConfig = new ClientConfiguration()
                .withConnectionTimeout(1000)
                .withMaxErrorRetry(5);

        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(provider,
                clientConfig, config.dynamoDBConfiguration());

        final ImmutableMap<DynamoDBTableName, String> tableNames = config.dynamoDBConfiguration().tables();

        final AmazonDynamoDB pillDataDAODynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_DATA);
        final PillDataDAODynamoDB pillDataDAODynamoDB = new PillDataDAODynamoDB(pillDataDAODynamoDBClient,
                tableNames.get(DynamoDBTableName.PILL_DATA));

        final AmazonDynamoDB deviceDataDAODynamoDBClient =  dynamoDBClientFactory.getForTable(DynamoDBTableName.DEVICE_DATA);
        final DeviceDataDAODynamoDB deviceDataDAODynamoDB = new DeviceDataDAODynamoDB(deviceDataDAODynamoDBClient,
                tableNames.get(DynamoDBTableName.DEVICE_DATA));

        final AmazonDynamoDB ringTimeHistoryDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.RING_TIME_HISTORY);
        final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB = new RingTimeHistoryDAODynamoDB(ringTimeHistoryDynamoDBClient,
                tableNames.get(DynamoDBTableName.RING_TIME_HISTORY));

        final AmazonDynamoDB sleepHmmDynamoDbClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_HMM);
        final SleepHmmDAODynamoDB sleepHmmDAODynamoDB = new SleepHmmDAODynamoDB(sleepHmmDynamoDbClient,
                tableNames.get(DynamoDBTableName.SLEEP_HMM));

        // use SQS version for testing
        final AmazonDynamoDB dynamoDBStatsClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_STATS);
        final SleepStatsDAODynamoDB sleepStatsDAODynamoDB = new SleepStatsDAODynamoDB(dynamoDBStatsClient,
                tableNames.get(DynamoDBTableName.SLEEP_STATS),
                config.getSleepStatsVersion());

        final AmazonDynamoDB onlineHmmModelsDb = dynamoDBClientFactory.getForTable(DynamoDBTableName.ONLINE_HMM_MODELS);
        final OnlineHmmModelsDAO onlineHmmModelsDAO = OnlineHmmModelsDAODynamoDB.create(onlineHmmModelsDb,
                tableNames.get(DynamoDBTableName.ONLINE_HMM_MODELS));

        final AmazonDynamoDB featureExtractionModelsDb = dynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURE_EXTRACTION_MODELS);
        final FeatureExtractionModelsDAO featureExtractionDAO = new FeatureExtractionModelsDAODynamoDB(featureExtractionModelsDb,
                tableNames.get(DynamoDBTableName.FEATURE_EXTRACTION_MODELS));

        final AmazonDynamoDB calibrationDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.CALIBRATION);
        final CalibrationDAO calibrationDAO = CalibrationDynamoDB.create(calibrationDynamoDBClient,
                tableNames.get(DynamoDBTableName.CALIBRATION));

        final AmazonDynamoDB sleepScoreParametersClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_SCORE_PARAMETERS);
        final SleepScoreParametersDAO sleepScoreParametersDAO = new SleepScoreParametersDynamoDB(sleepScoreParametersClient, tableNames.get(DynamoDBTableName.SLEEP_SCORE_PARAMETERS));

        /* Default model ensemble for all users  */
        final S3BucketConfiguration timelineModelEnsemblesConfig = config.getTimelineModelEnsemblesConfiguration();
        final S3BucketConfiguration seedModelConfig = config.getTimelineSeedModelConfiguration();

        final AmazonS3 amazonS3 = new AmazonS3Client(provider, clientConfig);
        final DefaultModelEnsembleDAO defaultModelEnsembleDAO = DefaultModelEnsembleFromS3.create(amazonS3,
                timelineModelEnsemblesConfig.getBucket(),
                timelineModelEnsemblesConfig.getKey(),
                seedModelConfig.getBucket(),
                seedModelConfig.getKey());

        /* Neural net endpoint information */
        final Map<AlgorithmType, URL> neuralNetEndpoints = config.getTaimurainConfiguration().getEndpoints();
        final Map<AlgorithmType, NeuralNetEndpoint> neuralNetClients = Maps.newHashMap();
        final HttpClientBuilder clientBuilder = new HttpClientBuilder(environment).using(config.getTaimurainConfiguration().getHttpClientConfiguration());

        for (final AlgorithmType algorithmType : neuralNetEndpoints.keySet()) {
            String url = neuralNetEndpoints.get(algorithmType).toExternalForm();
            final TaimurainHttpClient taimurainHttpClient = TaimurainHttpClient.create(
                    clientBuilder.build("taimurain" + algorithmType), url);
            neuralNetClients.put(algorithmType,taimurainHttpClient);
        }

        final PairingDAO pairingDAO = new HistoricalPairingDAO(deviceDAO,deviceDataDAODynamoDB);
        final com.hello.suripu.core.db.SenseDataDAO senseDataDAO = new SenseDataDAODynamoDB(pairingDAO, deviceDataDAODynamoDB, senseColorDAO, calibrationDAO);

        final AmazonDynamoDB timezoneHistoryDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.TIMEZONE_HISTORY);
        final TimeZoneHistoryDAODynamoDB timeZoneHistoryDAODynamoDB = new TimeZoneHistoryDAODynamoDB(timezoneHistoryDynamoDBClient, tableNames.get(DynamoDBTableName.TIMEZONE_HISTORY));

        final AmazonDynamoDB mainEventTimesDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.MAIN_EVENT_TIMES);
        final MainEventTimesDynamoDB mainEventTimesDAO = new MainEventTimesDynamoDB(
                mainEventTimesDynamoDBClient,
                tableNames.get(DynamoDBTableName.MAIN_EVENT_TIMES));

        final TimelineAlgorithmConfiguration timelineAlgorithmConfiguration = new TimelineAlgorithmConfiguration();
        return InstrumentedTimelineProcessor.createTimelineProcessor(
                pillDataDAODynamoDB,
                deviceDAO,
                deviceDataDAODynamoDB,
                ringTimeHistoryDAODynamoDB,
                feedbackDAO,
                sleepHmmDAODynamoDB,
                accountDAO,
                sleepStatsDAODynamoDB,
                mainEventTimesDAO,
                senseDataDAO,
                timeZoneHistoryDAODynamoDB,
                onlineHmmModelsDAO,
                featureExtractionDAO,
                defaultModelEnsembleDAO,
                userTimelineTestGroupDAO,
                sleepScoreParametersDAO,
                neuralNetClients,
                timelineAlgorithmConfiguration,
                environment.metrics());

    }

    private InstrumentedTimelineProcessorV3 createTimelineProcessorV3(final Environment environment,
                                                                      final AWSCredentialsProvider provider,
                                                                      final SuripuQueueConfiguration config)throws Exception {

        final DBIFactory factory = new DBIFactory();
        final DBI commonDB = factory.build(environment, config.getCommonDB(), "commonDB");

        commonDB.registerArgumentFactory(new JodaArgumentFactory());
        commonDB.registerContainerFactory(new OptionalContainerFactory());
        commonDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());
        commonDB.registerContainerFactory(new ImmutableListContainerFactory());
        commonDB.registerContainerFactory(new ImmutableSetContainerFactory());

        final DeviceReadDAO deviceDAO = commonDB.onDemand(DeviceReadDAO.class);
        final FeedbackReadDAO feedbackDAO = commonDB.onDemand(FeedbackReadDAO.class);
        final AccountDAO accountDAO = commonDB.onDemand(AccountDAOImpl.class);
        final SenseColorDAO senseColorDAO = commonDB.onDemand(SenseColorDAOSQLImpl.class);
        final UserTimelineTestGroupDAO userTimelineTestGroupDAO = commonDB.onDemand(UserTimelineTestGroupDAOImpl.class);

        final ClientConfiguration clientConfig = new ClientConfiguration()
                .withConnectionTimeout(1000)
                .withMaxErrorRetry(5);

        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(provider,
                clientConfig, config.dynamoDBConfiguration());

        final ImmutableMap<DynamoDBTableName, String> tableNames = config.dynamoDBConfiguration().tables();

        final AmazonDynamoDB pillDataDAODynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_DATA);
        final PillDataDAODynamoDB pillDataDAODynamoDB = new PillDataDAODynamoDB(pillDataDAODynamoDBClient,
                tableNames.get(DynamoDBTableName.PILL_DATA));

        final AmazonDynamoDB deviceDataDAODynamoDBClient =  dynamoDBClientFactory.getForTable(DynamoDBTableName.DEVICE_DATA);
        final DeviceDataDAODynamoDB deviceDataDAODynamoDB = new DeviceDataDAODynamoDB(deviceDataDAODynamoDBClient,
                tableNames.get(DynamoDBTableName.DEVICE_DATA));

        final AmazonDynamoDB ringTimeHistoryDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.RING_TIME_HISTORY);
        final RingTimeHistoryDAODynamoDB ringTimeHistoryDAODynamoDB = new RingTimeHistoryDAODynamoDB(ringTimeHistoryDynamoDBClient,
                tableNames.get(DynamoDBTableName.RING_TIME_HISTORY));

        final AmazonDynamoDB sleepHmmDynamoDbClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_HMM);
        final SleepHmmDAODynamoDB sleepHmmDAODynamoDB = new SleepHmmDAODynamoDB(sleepHmmDynamoDbClient,
                tableNames.get(DynamoDBTableName.SLEEP_HMM));

        // use SQS version for testing
        final AmazonDynamoDB dynamoDBStatsClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_STATS);
        final SleepStatsDAODynamoDB sleepStatsDAODynamoDB = new SleepStatsDAODynamoDB(dynamoDBStatsClient,
                tableNames.get(DynamoDBTableName.SLEEP_STATS),
                config.getSleepStatsVersion());

        final AmazonDynamoDB onlineHmmModelsDb = dynamoDBClientFactory.getForTable(DynamoDBTableName.ONLINE_HMM_MODELS);
        final OnlineHmmModelsDAO onlineHmmModelsDAO = OnlineHmmModelsDAODynamoDB.create(onlineHmmModelsDb,
                tableNames.get(DynamoDBTableName.ONLINE_HMM_MODELS));

        final AmazonDynamoDB featureExtractionModelsDb = dynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURE_EXTRACTION_MODELS);
        final FeatureExtractionModelsDAO featureExtractionDAO = new FeatureExtractionModelsDAODynamoDB(featureExtractionModelsDb,
                tableNames.get(DynamoDBTableName.FEATURE_EXTRACTION_MODELS));

        final AmazonDynamoDB calibrationDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.CALIBRATION);
        final CalibrationDAO calibrationDAO = CalibrationDynamoDB.create(calibrationDynamoDBClient,
                tableNames.get(DynamoDBTableName.CALIBRATION));

        final AmazonDynamoDB sleepScoreParametersClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.SLEEP_SCORE_PARAMETERS);
        final SleepScoreParametersDAO sleepScoreParametersDAO = new SleepScoreParametersDynamoDB(sleepScoreParametersClient, tableNames.get(DynamoDBTableName.SLEEP_SCORE_PARAMETERS));

        /* Default model ensemble for all users  */
        final S3BucketConfiguration timelineModelEnsemblesConfig = config.getTimelineModelEnsemblesConfiguration();
        final S3BucketConfiguration seedModelConfig = config.getTimelineSeedModelConfiguration();

        final AmazonS3 amazonS3 = new AmazonS3Client(provider, clientConfig);
        final DefaultModelEnsembleDAO defaultModelEnsembleDAO = DefaultModelEnsembleFromS3.create(amazonS3,
                timelineModelEnsemblesConfig.getBucket(),
                timelineModelEnsemblesConfig.getKey(),
                seedModelConfig.getBucket(),
                seedModelConfig.getKey());

        /* Neural net endpoint information */
        final Map<AlgorithmType, URL> neuralNetEndpoints = config.getTaimurainConfiguration().getEndpoints();
        final Map<AlgorithmType, NeuralNetEndpoint> neuralNetClients = Maps.newHashMap();
        final HttpClientBuilder clientBuilder = new HttpClientBuilder(environment).using(config.getTaimurainConfiguration().getHttpClientConfiguration());

        for (final AlgorithmType algorithmType : neuralNetEndpoints.keySet()) {
            String url = neuralNetEndpoints.get(algorithmType).toExternalForm();
            final TaimurainHttpClient taimurainHttpClient = TaimurainHttpClient.create(
                    clientBuilder.build("taimurain" + algorithmType), url);
            neuralNetClients.put(algorithmType,taimurainHttpClient);
        }

        final PairingDAO pairingDAO = new HistoricalPairingDAO(deviceDAO,deviceDataDAODynamoDB);
        final com.hello.suripu.core.db.SenseDataDAO senseDataDAO = new SenseDataDAODynamoDB(pairingDAO, deviceDataDAODynamoDB, senseColorDAO, calibrationDAO);

        final AmazonDynamoDB timezoneHistoryDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.TIMEZONE_HISTORY);
        final TimeZoneHistoryDAODynamoDB timeZoneHistoryDAODynamoDB = new TimeZoneHistoryDAODynamoDB(timezoneHistoryDynamoDBClient, tableNames.get(DynamoDBTableName.TIMEZONE_HISTORY));

        final AmazonDynamoDB mainEventTimesDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.MAIN_EVENT_TIMES);
        final MainEventTimesDynamoDB mainEventTimesDAO = new MainEventTimesDynamoDB(
                mainEventTimesDynamoDBClient,
                tableNames.get(DynamoDBTableName.MAIN_EVENT_TIMES));

        final TimelineAlgorithmConfiguration timelineAlgorithmConfiguration = new TimelineAlgorithmConfiguration();
        return InstrumentedTimelineProcessorV3.createTimelineProcessor(
                pillDataDAODynamoDB,
                deviceDAO,
                deviceDataDAODynamoDB,
                ringTimeHistoryDAODynamoDB,
                feedbackDAO,
                sleepHmmDAODynamoDB,
                accountDAO,
                sleepStatsDAODynamoDB,
                mainEventTimesDAO,
                senseDataDAO,
                timeZoneHistoryDAODynamoDB,
                onlineHmmModelsDAO,
                featureExtractionDAO,
                defaultModelEnsembleDAO,
                userTimelineTestGroupDAO,
                sleepScoreParametersDAO,
                neuralNetClients,
                timelineAlgorithmConfiguration,
                environment.metrics());

    }

}

