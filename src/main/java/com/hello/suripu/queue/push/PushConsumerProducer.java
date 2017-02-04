package com.hello.suripu.queue.push;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.buffered.AmazonSQSBufferedAsyncClient;
import com.amazonaws.services.sqs.buffered.QueueBufferConfig;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hello.suripu.api.queue.TimelineQueueProtos;
import com.hello.suripu.core.db.PillDataDAODynamoDB;
import com.hello.suripu.core.models.TrackerMotion;
import com.hello.suripu.queue.configuration.SQSConfiguration;
import com.hello.suripu.queue.timeline.TimelineQueueProcessor;
import io.dropwizard.lifecycle.Managed;
import org.apache.commons.codec.binary.Base64;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ExecutorService;

public class PushConsumerProducer implements Managed{

    private static final Logger LOGGER = LoggerFactory.getLogger(PushConsumerProducer.class);

    private final PillDataDAODynamoDB pillDataDAODynamoDB;
    private final ExecutorService consumerExecutor;
    private final SQSConfiguration sqsOutConfiguration;
    private final SQSConfiguration sqsInConfiguration;

    private volatile boolean isRunning = false;

    public PushConsumerProducer(final PillDataDAODynamoDB pillDataDAODynamoDB,
                                final ExecutorService consumerExecutor,
                                final SQSConfiguration sqsOutConfiguration,
                                final SQSConfiguration sqsInConfiguration) {
        this.pillDataDAODynamoDB = pillDataDAODynamoDB;
        this.consumerExecutor = consumerExecutor;
        this.sqsOutConfiguration = sqsOutConfiguration;
        this.sqsInConfiguration = sqsInConfiguration;
    }

    @Override
    public void start() throws Exception {
        consumerExecutor.execute(() -> {
            try {
                LOGGER.debug("key=suripu-queue-trigger-consumer action=started");
                isRunning = true;
                processMessages();
            } catch (Exception exception) {
                isRunning = false;
                LOGGER.error("key=suripu-queue-trigger error=fail-to-start-consumer-thread msg={}", exception.getMessage());
                LOGGER.error("key=suripu-queue-trigger error=dead-forcing-exit");
                try {
                    // sleep 5 secs messages to flush
                    Thread.sleep(5000L);
                } catch (InterruptedException ignored) {
                }
                System.exit(1);
            }
        });
    }

    @Override
    public void stop() throws Exception {
        LOGGER.debug("key=suripu-queue-trigger action=stopped");
        isRunning = false;
    }

    private void processMessages() throws Exception {

        final QueueBufferConfig config = new QueueBufferConfig()
                .withMaxInflightReceiveBatches(5)
                .withMaxDoneReceiveBatches(15);

        final AmazonSQSAsync amazonSQSAsync = new AmazonSQSAsyncClient(new DefaultAWSCredentialsProviderChain());
        final AmazonSQS amazonSQS = new AmazonSQSBufferedAsyncClient(amazonSQSAsync, config);
        final AmazonSQS syncClient = new AmazonSQSClient(new DefaultAWSCredentialsProviderChain());

        final Optional<String> producerQueueUrl = TimelineQueueProcessor.getSQSQueueURL(amazonSQSAsync, sqsOutConfiguration.getSqsQueueName());
        if(!producerQueueUrl.isPresent()) {
            LOGGER.error("key=suripu-queue-trigger missing=sqs-queue-url");
            System.exit(1);
        }

        final Optional<String> consumerQueueUrl = TimelineQueueProcessor.getSQSQueueURL(amazonSQSAsync, sqsInConfiguration.getSqsQueueName());
        if(!producerQueueUrl.isPresent()) {
            LOGGER.error("key=suripu-queue-trigger missing=sqs-queue-url");
            System.exit(1);
        }


        while (isRunning) {

            final ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest()
                    .withWaitTimeSeconds(sqsInConfiguration.getSqsWaitTimeSeconds())
                    .withMaxNumberOfMessages(sqsInConfiguration.getSqsMaxMessage())
                    .withVisibilityTimeout(sqsInConfiguration.getSqsVisibilityTimeoutSeconds())
                    .withQueueUrl(consumerQueueUrl.get());

            final ReceiveMessageResult result = syncClient.receiveMessage(receiveMessageRequest);
            LOGGER.info("action=receive-message msg_count={}", result.getMessages().size());

            final Set<String> toDelete = Sets.newHashSet();
            for(final Message message : result.getMessages()) {
                try {
                    final TimelineQueueProtos.TriggerMessage triggerMessage = TimelineQueueProtos.TriggerMessage.parseFrom(Base64.decodeBase64(message.getBody()));
                    final Long accountId = triggerMessage.getAccountId();
                    final ImmutableList<TrackerMotion> trackerMotionList = pillDataDAODynamoDB.getBetween(accountId, DateTime.now(DateTimeZone.UTC).minusMinutes(20), DateTime.now(DateTimeZone.UTC));

                    final boolean pillSeenRecently = true; // TODO: lookup last seen
                    LOGGER.info("action=check-motion motion_count={} account_id={} pill_seen_recently={}", trackerMotionList.size(), accountId, pillSeenRecently);
                    if (trackerMotionList.isEmpty() && pillSeenRecently) {

                        final TimelineQueueProtos.Message protoMessage = TimelineQueueProtos.Message.newBuilder()
                                .setAccountId(accountId)
                                .setTargetDate(triggerMessage.getTargetDate())
                                .setTimestamp(DateTime.now(DateTimeZone.UTC).getMillis()).build();
                        final SendMessageRequest req = new SendMessageRequest()
                                .withMessageBody(Base64.encodeBase64URLSafeString(protoMessage.toByteArray()))
                                .withQueueUrl(producerQueueUrl.get());
                        amazonSQS.sendMessage(req);
                        toDelete.add(message.getReceiptHandle());
                        LOGGER.info("action=send-timeline-message account_id={}", accountId);
                    }

                } catch (InvalidProtocolBufferException protoException) {
                    LOGGER.error("action=parse-proto error={}", protoException.getMessage());
                    toDelete.add(message.getReceiptHandle());
                } catch (Exception e) {
                    LOGGER.error("error={}", e.getMessage());
                }
            }

            // Cleanup messages that should be deleted
            for(final String receiptHandle : toDelete) {
                final DeleteMessageRequest deleteMessageRequest = new DeleteMessageRequest()
                        .withReceiptHandle(receiptHandle)
                        .withQueueUrl(consumerQueueUrl.get());
                LOGGER.info("action=delete-message handle={}", receiptHandle);
                amazonSQSAsync.deleteMessageAsync(deleteMessageRequest);
            }
        }
    }
}
