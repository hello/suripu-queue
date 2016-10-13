package com.hello.suripu.queue.timeline;

import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessor;
import io.dropwizard.lifecycle.Managed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Created by ksg on 3/15/16
 * docs: http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/Welcome.html
 */

public class TimelineQueueConsumerManager implements Managed {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimelineQueueConsumerManager.class);

    private static final long SLEEP_WHEN_NO_MESSAGES_MILLIS = 10000L; // 10 secs

    private final TimelineQueueProcessor queueProcessor;
    private final InstrumentedTimelineProcessor timelineProcessor;

    private final ExecutorService timelineExecutor;
    private final ExecutorService consumerExecutor;

    private boolean isRunning = false; // mutable

    // metrics
    private final Meter messagesProcessed;
    private final Meter messagesReceived;
    private final Meter messagesDeleted;
    private final Meter validSleepScore;
    private final Meter invalidSleepScore;
    private final Meter noTimeline;

    private long totalMessagesProcessed;
    private long totalIdleIterations;
    private long totalRunningIterations;

    public TimelineQueueConsumerManager(final TimelineQueueProcessor queueProcessor,
                                        final InstrumentedTimelineProcessor timelineProcessor,
                                        final ExecutorService consumerExecutor,
                                        final ExecutorService timelineExecutors,
                                        final MetricRegistry metrics
                                        ) {
        this.queueProcessor = queueProcessor;
        this.timelineProcessor = timelineProcessor;
        this.timelineExecutor = timelineExecutors;
        this.consumerExecutor = consumerExecutor;

        // metrics
        final Class klass = TimelineQueueConsumerManager.class;
        this.messagesProcessed = metrics.meter(MetricRegistry.name(klass, "processed", "messages-processed"));
        this.messagesReceived = metrics.meter(MetricRegistry.name(klass, "received", "messages-received"));
        this.messagesDeleted = metrics.meter(MetricRegistry.name(klass, "deleted", "messages-deleted"));
        this.validSleepScore = metrics.meter(MetricRegistry.name(klass, "ok-sleep-score", "valid-score"));
        this.invalidSleepScore = metrics.meter(MetricRegistry.name(klass, "invalid-sleep-score", "invalid-score"));
        this.noTimeline = metrics.meter(MetricRegistry.name(klass, "timeline-fail", "fail-to-created"));

    }

    public long getProcessed() {
        return this.totalMessagesProcessed;
    }

    public long getRunningIterations() {
        return this.totalRunningIterations;
    }

    public long getIdleIterations() {
        return this.totalIdleIterations;
    }

    @Override
    public void start() throws Exception {
        consumerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LOGGER.debug("key=suripu-queue-consumer action=started");
                    isRunning = true;
                    processMessages();
                } catch (Exception exception) {
                    isRunning = false;
                    LOGGER.error("key=suripu-queue-consumer error=fail-to-start-consumer-thread msg={}", exception.getMessage());
                    LOGGER.error("key=suripu-queue-consumer error=dead-forcing-exit");
                    try {
                        // sleep 5 secs messages to flush
                        Thread.sleep(5000L);
                    } catch (InterruptedException ignored) {
                    }
                    System.exit(1);
                }
            }
        });

    }

    @Override
    public void stop() throws Exception {
        LOGGER.debug("key=suripu-queue-consumer action=stopped");
        isRunning = false;
    }


    private void processMessages() throws Exception {

        int numEmptyQueueIterations = 0;
        do {
            // get a bunch of messages from SQS
            final List<TimelineQueueProcessor.TimelineMessage> messages = queueProcessor.receiveMessages();

            messagesReceived.mark(messages.size());

            final List<Future<TimelineQueueProcessor.TimelineMessage>> futures = Lists.newArrayListWithCapacity(messages.size());

            if (!messages.isEmpty()) {
                this.totalRunningIterations++;
                LOGGER.debug("key=suripu-queue-consumer action=processing size={}", messages.size());

                // generate all the timelines
                for (final TimelineQueueProcessor.TimelineMessage message : messages) {
                    final TimelineGenerator generator = new TimelineGenerator(this.timelineProcessor, message);
                    final Future<TimelineQueueProcessor.TimelineMessage> future = timelineExecutor.submit(generator);
                    futures.add(future);
                }

                // prepare to delete processed messages
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

                // delete messages
                if (!processedHandlers.isEmpty()) {
                    LOGGER.debug("key=suripu-queue-consumer action=delete-messages num={}", processedHandlers.size());
                    final int deleted = queueProcessor.deleteMessages(processedHandlers);
                    messagesProcessed.mark(processedHandlers.size());
                    messagesDeleted.mark(deleted);
                    this.totalMessagesProcessed += processedHandlers.size();
                }

                numEmptyQueueIterations = 0;

            } else {
                numEmptyQueueIterations++;
                LOGGER.info("key=suripu-queue-consumer action=empty-iteration value={}", numEmptyQueueIterations);
                Thread.sleep(SLEEP_WHEN_NO_MESSAGES_MILLIS);
                this.totalIdleIterations++;
            }

        } while (isRunning);

        LOGGER.info("key=suripu-queue-consumer action=process-messages-done");
    }

}
