package com.hello.suripu.queue;

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
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.hello.suripu.core.ObjectGraphRoot;
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
import com.hello.suripu.core.db.OnlineHmmModelsDAO;
import com.hello.suripu.core.db.OnlineHmmModelsDAODynamoDB;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.db.RingTimeHistoryDAODynamoDB;
import com.hello.suripu.core.db.SleepStatsDAODynamoDB;
import com.hello.suripu.core.db.UserTimelineTestGroupDAO;
import com.hello.suripu.core.db.UserTimelineTestGroupDAOImpl;
import com.hello.suripu.core.db.colors.SenseColorDAO;
import com.hello.suripu.core.db.colors.SenseColorDAOSQLImpl;
import com.hello.suripu.core.db.util.JodaArgumentFactory;
import com.hello.suripu.core.db.util.PostgresIntegerArrayArgumentFactory;
import com.hello.suripu.core.processors.TimelineProcessor;
import com.hello.suripu.coredw8.clients.AmazonDynamoDBClientFactory;
import com.hello.suripu.coredw8.clients.TaimurainHttpClient;
import com.hello.suripu.coredw8.configuration.S3BucketConfiguration;
import com.hello.suripu.coredw8.configuration.TaimurainHttpClientConfiguration;
import com.hello.suripu.coredw8.db.SleepHmmDAODynamoDB;
import com.hello.suripu.queue.cli.PopulateTimelineQueueCommand;
import com.hello.suripu.queue.cli.TimelineQueueWorkerCommand;
import com.hello.suripu.queue.configuration.SQSConfiguration;
import com.hello.suripu.queue.configuration.SuripuQueueConfiguration;
import com.hello.suripu.queue.models.QueueHealthCheck;
import com.hello.suripu.queue.models.SenseDataDAO;
import com.hello.suripu.queue.modules.RolloutQueueModule;
import com.hello.suripu.queue.resources.ConfigurationResource;
import com.hello.suripu.queue.resources.StatsResource;
import com.hello.suripu.queue.timeline.TimelineQueueConsumerManager;
import com.hello.suripu.queue.timeline.TimelineQueueProcessor;
import com.hello.suripu.queue.timeline.TimelineQueueProducerManager;
import io.dropwizard.Application;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Duration;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SuripuQueue extends Application<SuripuQueueConfiguration> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SuripuQueue.class);

    private static int MIN_THREAD_SIZE = 2;

    public static void main(final String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        new SuripuQueue().run(args);
    }

    @Override
    public void initialize(Bootstrap<SuripuQueueConfiguration> bootstrap) {
        bootstrap.addCommand(new TimelineQueueWorkerCommand());
        bootstrap.addCommand(new PopulateTimelineQueueCommand());
    }

    @Override
    public void run(final SuripuQueueConfiguration configuration, final Environment environment) throws Exception {

        if(configuration.getMetricsEnabled()) {
            final String graphiteHostName = configuration.getGraphite().getHost();
            final String apiKey = configuration.getGraphite().getApiKey();
            final Integer interval = configuration.getGraphite().getReportingIntervalInSeconds();

            final String env = (configuration.getDebug()) ? "dev" : "prod";

            final String prefix = String.format("%s.%s.%s", apiKey, env, "suripu-queue");

            final Graphite graphite = new Graphite(new InetSocketAddress(graphiteHostName, 2003));
            final GraphiteReporter reporter = GraphiteReporter.forRegistry(environment.metrics())
                    .prefixedWith(prefix)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .filter(MetricFilter.ALL)
                    .build(graphite);
            reporter.start(interval, TimeUnit.SECONDS);


            LOGGER.info("key=suripu-queue action=metrics-enabled.");
        } else {
            LOGGER.warn("key=suripu-queue action=metrics-disabled.");
        }

        final AWSCredentialsProvider provider = new DefaultAWSCredentialsProviderChain();

        // setup SQS
        final SQSConfiguration sqsConfig = configuration.getSqsConfiguration();
        final int maxConnections = sqsConfig.getSqsMaxConnections();
        final AmazonSQSAsync sqsClient = new AmazonSQSBufferedAsyncClient(
                new AmazonSQSAsyncClient(provider, new ClientConfiguration()
                .withMaxConnections(maxConnections)
                .withConnectionTimeout(500)));

        final Region region = Region.getRegion(Regions.US_EAST_1);
        sqsClient.setRegion(region);

        // get SQS queue url
        final Optional<String> optionalSqsQueueUrl = TimelineQueueProcessor.getSQSQueueURL(sqsClient, sqsConfig.getSqsQueueName());
        if (!optionalSqsQueueUrl.isPresent()) {
            LOGGER.error("key=suripu-queue error=no-sqs-queue-found queue-name={}", sqsConfig.getSqsQueueName());
            throw new Exception("Invalid queue name");
        }

        final String sqsQueueUrl = optionalSqsQueueUrl.get();

        final DBIFactory factory = new DBIFactory();
        final DBI commonDB = factory.build(environment, configuration.getCommonDB(), "commonDB");
        final DBI sensorDB = factory.build(environment, configuration.getSensorDB(), "redshift-sensor-db");

        sensorDB.registerArgumentFactory(new JodaArgumentFactory());
        sensorDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());

        final SenseDataDAO senseDataDAO = sensorDB.onDemand(SenseDataDAO.class);

        // stuff needed to create timeline processor
        commonDB.registerArgumentFactory(new JodaArgumentFactory());
        commonDB.registerArgumentFactory(new PostgresIntegerArrayArgumentFactory());

        final DeviceReadDAO deviceDAO = commonDB.onDemand(DeviceReadDAO.class);
        final FeedbackReadDAO feedbackDAO = commonDB.onDemand(FeedbackReadDAO.class);
        final AccountDAO accountDAO = commonDB.onDemand(AccountDAOImpl.class);
        final SenseColorDAO senseColorDAO = commonDB.onDemand(SenseColorDAOSQLImpl.class);
        final UserTimelineTestGroupDAO userTimelineTestGroupDAO = commonDB.onDemand(UserTimelineTestGroupDAOImpl.class);

        final ClientConfiguration clientConfig = new ClientConfiguration()
                .withConnectionTimeout(1000)
                .withMaxErrorRetry(5);

        final AmazonDynamoDBClientFactory dynamoDBClientFactory = AmazonDynamoDBClientFactory.create(provider,
                clientConfig, configuration.dynamoDBConfiguration());

        final ImmutableMap<DynamoDBTableName, String> tableNames = configuration.dynamoDBConfiguration().tables();

        final AmazonDynamoDB featuresDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURES);
        final FeatureStore featureStore = new FeatureStore(featuresDynamoDBClient, configuration.dynamoDBConfiguration().tables().get(DynamoDBTableName.FEATURES), "prod");

        final RolloutQueueModule module = new RolloutQueueModule(featureStore, 30);
        ObjectGraphRoot.getInstance().init(module);

        final AmazonDynamoDB pillDataDAODynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.PILL_DATA);
        final PillDataDAODynamoDB pillDataDAODynamoDB = new PillDataDAODynamoDB(pillDataDAODynamoDBClient,
                tableNames.get(DynamoDBTableName.PILL_DATA));

        final AmazonDynamoDB deviceDataDAODynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.DEVICE_DATA);
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
                configuration.getSleepStatsVersion());

        final AmazonDynamoDB onlineHmmModelsDb = dynamoDBClientFactory.getForTable(DynamoDBTableName.ONLINE_HMM_MODELS);
        final OnlineHmmModelsDAO onlineHmmModelsDAO = OnlineHmmModelsDAODynamoDB.create(onlineHmmModelsDb,
                tableNames.get(DynamoDBTableName.ONLINE_HMM_MODELS));

        final AmazonDynamoDB featureExtractionModelsDb = dynamoDBClientFactory.getForTable(DynamoDBTableName.FEATURE_EXTRACTION_MODELS);
        final FeatureExtractionModelsDAO featureExtractionDAO = new FeatureExtractionModelsDAODynamoDB(featureExtractionModelsDb,
                tableNames.get(DynamoDBTableName.FEATURE_EXTRACTION_MODELS));

        final AmazonDynamoDB calibrationDynamoDBClient = dynamoDBClientFactory.getForTable(DynamoDBTableName.CALIBRATION);
        final CalibrationDAO calibrationDAO = CalibrationDynamoDB.create(calibrationDynamoDBClient,
                tableNames.get(DynamoDBTableName.CALIBRATION));

        /* Default model ensemble for all users  */
        final S3BucketConfiguration timelineModelEnsemblesConfig = configuration.getTimelineModelEnsemblesConfiguration();
        final S3BucketConfiguration seedModelConfig = configuration.getTimelineSeedModelConfiguration();

        final AmazonS3 amazonS3 = new AmazonS3Client(provider, clientConfig);
        final DefaultModelEnsembleDAO defaultModelEnsembleDAO = DefaultModelEnsembleFromS3.create(amazonS3,
                timelineModelEnsemblesConfig.getBucket(),
                timelineModelEnsemblesConfig.getKey(),
                seedModelConfig.getBucket(),
                seedModelConfig.getKey());

        /* Neural net endpoint information */
        final TaimurainHttpClientConfiguration taimurainHttpClientConfiguration = configuration.getTaimurainHttpClientConfiguration();

        final TaimurainHttpClient taimurainHttpClient = TaimurainHttpClient.create(
                new HttpClientBuilder(environment)
                        .using(taimurainHttpClientConfiguration.getHttpClientConfiguration())
                        .build("taimurain"),
                taimurainHttpClientConfiguration.getEndpoint());


        final TimelineProcessor timelineProcessor = TimelineProcessor.createTimelineProcessor(
                pillDataDAODynamoDB,
                deviceDAO,
                deviceDataDAODynamoDB,
                ringTimeHistoryDAODynamoDB,
                feedbackDAO,
                sleepHmmDAODynamoDB,
                accountDAO,
                sleepStatsDAODynamoDB,
                senseColorDAO,
                onlineHmmModelsDAO,
                featureExtractionDAO,
                calibrationDAO,
                defaultModelEnsembleDAO,
                userTimelineTestGroupDAO,
                taimurainHttpClient);


        final long keepAliveTimeSeconds = 2L;

        // create queue consumer
        final TimelineQueueProcessor queueProcessor = new TimelineQueueProcessor(sqsQueueUrl, sqsClient, configuration.getSqsConfiguration());

        // thread pool to run consumer
        final ExecutorService consumerExecutor = environment.lifecycle().executorService("consumer")
                .minThreads(MIN_THREAD_SIZE)
                .maxThreads(configuration.getNumConsumerThreads())
                .keepAliveTime(Duration.seconds(keepAliveTimeSeconds)).build();

        // thread pool to compute timelines
        final ExecutorService timelineExecutor = environment.lifecycle().executorService("consumer_timeline_processor")
                .minThreads(MIN_THREAD_SIZE)
                .maxThreads(configuration.getNumTimelineThreads())
                .keepAliveTime(Duration.seconds(keepAliveTimeSeconds)).build();

        final TimelineQueueConsumerManager consumerManager = new TimelineQueueConsumerManager(queueProcessor,
                timelineProcessor, consumerExecutor, timelineExecutor, environment.metrics());

        environment.lifecycle().manage(consumerManager);


        // create queue producer to insert messages into sqs queue

        // Thread pool to send batch messages in parallel
        final ExecutorService sendMessageExecutor = environment.lifecycle().executorService("producer_send_message")
                .minThreads(MIN_THREAD_SIZE)
                .maxThreads(configuration.getNumSendMessageThreads())
                .keepAliveTime(Duration.seconds(keepAliveTimeSeconds)).build();

        // Thread pool to run producer thread in a fix schedule
        final ScheduledExecutorService producerExecutor = environment.lifecycle().scheduledExecutorService("producer")
                .threads(configuration.getNumProducerThreads()).build();

        final TimelineQueueProducerManager producerManager = new TimelineQueueProducerManager(
                sqsClient,
                senseDataDAO,
                sqsQueueUrl,
                producerExecutor,
                sendMessageExecutor,
                configuration.getProducerScheduleIntervalMinutes(),
                configuration.getNumProducerThreads(),
                environment.metrics());

        environment.lifecycle().manage(producerManager);

        final QueueHealthCheck queueHealthCheck = new QueueHealthCheck("suripu-queue", sqsClient, sqsQueueUrl);
        environment.healthChecks().register("suripu-queue", queueHealthCheck);

        environment.jersey().register(new StatsResource(producerManager, consumerManager));
        environment.jersey().register(new ConfigurationResource(configuration));
    }
}
