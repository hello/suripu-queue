package com.hello.suripu.queue.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hello.suripu.coredropwizard.configuration.GraphiteConfiguration;
import com.hello.suripu.coredropwizard.configuration.KinesisConfiguration;
import com.hello.suripu.coredropwizard.configuration.NewDynamoDBConfiguration;
import com.hello.suripu.coredropwizard.configuration.S3BucketConfiguration;
import com.hello.suripu.coredropwizard.configuration.TaimurainConfiguration;
import io.dropwizard.Configuration;
import io.dropwizard.db.DataSourceFactory;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class SuripuQueueConfiguration extends Configuration{

    public SuripuQueueConfiguration() {
    }

    @Valid
    @NotNull
    @JsonProperty("common_db")
    private DataSourceFactory commonDB = new DataSourceFactory();
    public DataSourceFactory getCommonDB() { return commonDB; }

    @Valid
    @NotNull
    @JsonProperty("sensors_db")
    private DataSourceFactory sensorDB = new DataSourceFactory();
    public DataSourceFactory getSensorDB() {
        return sensorDB;
    }

    @Valid
    @NotNull
    @JsonProperty("metrics_enabled")
    private Boolean metricsEnabled;
    public Boolean getMetricsEnabled() {
        return metricsEnabled;
    }

    @Valid
    @JsonProperty("debug")
    private Boolean debug = Boolean.FALSE;

    public Boolean getDebug() {
        return debug;
    }

    @Valid
    @NotNull
    @JsonProperty("graphite")
    private GraphiteConfiguration graphite;
    public GraphiteConfiguration getGraphite() {
        return graphite;
    }

    @Valid
    @NotNull
    @JsonProperty("sleep_stats_version")
    private String sleepStatsVersion;
    public String getSleepStatsVersion() { return this.sleepStatsVersion; }

    @Valid
    @NotNull
    @JsonProperty("timeline_model_ensembles")
    private S3BucketConfiguration timelineModelEnsemblesConfiguration;
    public S3BucketConfiguration getTimelineModelEnsemblesConfiguration() { return timelineModelEnsemblesConfiguration; }

    @Valid
    @NotNull
    @JsonProperty("timeline_seed_model")
    private S3BucketConfiguration timelineSeedModelConfiguration;
    public S3BucketConfiguration getTimelineSeedModelConfiguration() { return timelineSeedModelConfiguration; }

    @Valid
    @NotNull
    @JsonProperty("SQS_configuration")
    private SQSConfiguration sqsConfiguration = new SQSConfiguration();
    public SQSConfiguration getSqsConfiguration() { return this.sqsConfiguration; }

    @Valid
    @NotNull
    @JsonProperty("sleep_score_sqs_configuration")
    private SQSConfiguration sleepScoreSqsConfiguration;
    public SQSConfiguration sleepScoreSqsConfiguration() {
        return sleepScoreSqsConfiguration;
    }

    @Valid
    @NotNull
    @JsonProperty("dynamodb")
    private NewDynamoDBConfiguration dynamoDBConfiguration;
    public NewDynamoDBConfiguration dynamoDBConfiguration(){
        return dynamoDBConfiguration;
    }


    @JsonProperty("num_queue_consumer_threads")
    @Max(20)
    @Min(1)
    private int numConsumerThreads = 2;
    public int getNumConsumerThreads() { return this.numConsumerThreads; }

    @JsonProperty("num_compute_timeline_threads")
    @Max(20)
    @Min(1)
    private int numTimelineThreads = 5;
    public int getNumTimelineThreads() { return this.numTimelineThreads; }

    @JsonProperty("num_queue_producer_threads")
    @Max(10)
    @Min(1)
    private int numProducerThreads = 2;
    public int getNumProducerThreads() { return this.numProducerThreads; }

    @JsonProperty("num_send_message_threads")
    @Max(10)
    @Min(1)
    private int numSendMessageThreads = 5;
    public int getNumSendMessageThreads() { return this.numSendMessageThreads; }

    @JsonProperty("queue_producer_interval_minutes")
    @Max(30)
    @Min(1)
    private long producerScheduleIntervalMinutes = 10L;
    public long getProducerScheduleIntervalMinutes() { return this.producerScheduleIntervalMinutes; }

    @NotNull
    @JsonProperty("taimurain_configuration")
    private TaimurainConfiguration taimurainConfiguration;
    public TaimurainConfiguration getTaimurainConfiguration() {
        return taimurainConfiguration;
    }

    @NotNull
    @JsonProperty("push_notification_configuration")
    private KinesisConfiguration pushNotificationConfiguration;
    public KinesisConfiguration pushNotificationConfiguration() {
        return pushNotificationConfiguration;
    }
}
